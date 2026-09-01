package com.bugtracking.service;

import com.bugtracking.model.Notification;
import com.bugtracking.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * In-app notifications for the events the BRD calls out: assigned, fixed,
 * reopened and closed. No email is sent - that needs a mail server and real
 * user accounts, neither of which exists yet.
 */
@Service
@Transactional
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /** A notification with nobody to send it to is not worth storing. */
    public void notify(Long bugId, String type, String recipient, String message) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        repository.save(new Notification(bugId, type, recipient.trim(), message));
    }

    @Transactional(readOnly = true)
    public List<Notification> recent() {
        return repository.findTop50ByOrderByCreatedAtDescIdDesc();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByReadFalse();
    }

    public void markRead(Long id) {
        repository.findById(id).ifPresent(n -> {
            n.setRead(true);
            repository.save(n);
        });
    }

    public int markAllRead() {
        List<Notification> unread = repository.findByReadFalse();
        unread.forEach(n -> n.setRead(true));
        repository.saveAll(unread);
        return unread.size();
    }

    public void deleteForBug(Long bugId) {
        repository.deleteByBugId(bugId);
    }
}
