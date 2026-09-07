# Public bug intake: integration guide

Every project in the bug tracker has a **public link**. Anyone holding it can file a bug on that project without an account, either through the hosted form or through the API below. Bugs filed this way arrive unassigned, in the project's first board column, marked **External**, and the project's team is notified.

## 1. Get the project's link

Sign in to the tracker, select the project, and click the link icon beside the project name in the top bar. The clipboard now holds:

```
https://<tracker-host>/public/<token>
```

The same button is on every row of Settings → Projects. The `<token>` is the only credential. Treat it like a shared secret: anyone with it can file bugs on that project. An admin can regenerate it from Settings → Projects → Regenerate, which kills the old link immediately.

## 2. Option A: link to the hosted form

Send users to the link as-is. The form asks for name, email, title, description, severity, environment and up to three screenshots, and ends on a "Thanks, BUG-n" page. Nothing to build.

## 3. Option B: call the API

```
POST https://<tracker-host>/api/public/<token>/bugs
Content-Type: multipart/form-data
```

No authentication header, no cookie, no CSRF token.

### Fields

| Field | Required | Rules |
|---|---|---|
| `reporterName` | yes | 1 to 80 characters |
| `reporterEmail` | yes | valid email, up to 200 characters; stored lowercase and shown to the team as a mailto |
| `title` | yes | 1 to 150 characters |
| `description` | yes | 1 to 4000 characters. The request's `User-Agent` is appended as a `Browser:` line, trimmed to fit |
| `severity` | yes | one of `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `environment` | yes | one of `QA`, `UAT`, `PRODUCTION` |
| `files` | no | repeat the field for each file, at most 3 are stored |

Accepted file types: `png`, `jpeg`, `gif`, `webp`, `bmp`, `heic`, `heif`, `pdf`, `mp4`, `webm`, `ogg`, `mov`. Size limits: images 8 MB, PDFs 25 MB, video 64 MB. A refused file does **not** fail the request; the bug is still created and the refusal comes back in `rejected`.

Send the `User-Agent` of the end user's browser if you are proxying the call; it is the only browser detail captured.

### Responses

| Status | Body | Meaning |
|---|---|---|
| `201` | `{"bugId": 42, "rejected": null}` | Filed. `rejected` is a sentence naming any file that was not attached, otherwise `null` |
| `400` | `{"errors": {"reporterEmail": "That does not look like an email address"}}` | Validation failed; one message per field |
| `400` | `{"error": "..."}` | A file could not be accepted at all |
| `404` | `{"error": "This link is not valid any more."}` | Unknown token, or the project was hidden or the link regenerated |
| `413` | `{"error": "..."}` | A single upload exceeded the server's request limit |
| `429` | `{"error": "..."}` | More than 12 reports from one IP address in an hour |

### Examples

curl:

```bash
curl -X POST "https://<tracker-host>/api/public/<token>/bugs" \
  -F reporterName="Priya Nair" \
  -F reporterEmail="priya@example.com" \
  -F title="Sign-in swallows the first click" \
  -F description="Open /login, click Sign in once. Nothing happens; second click works." \
  -F severity=HIGH \
  -F environment=PRODUCTION \
  -F files=@screenshot.png \
  -F files=@recording.mp4
```

Browser `fetch`:

```js
const form = new FormData();
form.append("reporterName", name);
form.append("reporterEmail", email);
form.append("title", title);
form.append("description", description);
form.append("severity", "MEDIUM");
form.append("environment", "PRODUCTION");
for (const file of fileInput.files) form.append("files", file);

const res = await fetch(`https://<tracker-host>/api/public/${token}/bugs`, {
  method: "POST",
  body: form, // do not set Content-Type; the browser adds the multipart boundary
});
const data = await res.json();
if (res.status === 201) {
  showThanks(data.bugId, data.rejected);
} else if (res.status === 400 && data.errors) {
  showFieldErrors(data.errors);
} else {
  showError(data.error);
}
```

## 4. Behaviour to expect

- The bug's reporter is the `reporterName` you send; the email is visible to the team only.
- The bug is unassigned. Assignment, column, project and due date cannot be set from outside.
- Cards, list rows and the detail page show an **External** mark, and the board's "Came from" filter has a "The public link" option.
- The project's team gets a bell notification and, where mail is configured, an email.
- Rate limiting is per client IP, sliding window of one hour, 12 reports. Behind a proxy the whole proxy counts as one address, so batch integrations should expect `429` and retry later.

## 5. Checklist before going live

- [ ] Copied the link for the right project, not the one you happened to be viewing
- [ ] Stored the token server-side or in config, not in a public repository
- [ ] Handled `201` with a non-null `rejected`, `400` with `errors`, and `404` after a regenerate
- [ ] Forwarded the end user's `User-Agent` when calling from a backend
