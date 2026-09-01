package com.bugtracking.repository;

import com.bugtracking.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByBugIdOrderByUploadedAtAsc(Long bugId);

    long countByBugId(Long bugId);
}
