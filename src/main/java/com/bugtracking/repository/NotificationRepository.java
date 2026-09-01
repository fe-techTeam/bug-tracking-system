package com.bugtracking.repository;

import com.bugtracking.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByOrderByCreatedAtDescIdDesc();

    long countByReadFalse();

    List<Notification> findByReadFalse();

    void deleteByBugId(Long bugId);
}
