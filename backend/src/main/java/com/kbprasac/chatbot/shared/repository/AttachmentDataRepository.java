package com.kbprasac.chatbot.shared.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.AttachmentData;

public interface AttachmentDataRepository extends JpaRepository<AttachmentData, UUID> {
}
