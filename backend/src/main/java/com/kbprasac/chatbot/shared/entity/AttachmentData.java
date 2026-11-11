package com.kbprasac.chatbot.shared.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The bytes of an {@link Attachment}, in their own table so lists never load files. */
@Entity
@Table(name = "attachment_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentData {

    @Id
    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(nullable = false)
    private byte[] data;
}
