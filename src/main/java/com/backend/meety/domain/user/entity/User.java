package com.backend.meety.domain.user.entity;

import com.backend.meety.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voice_sample_key")
    private String voiceSampleKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "voice_embedding", columnDefinition = "json")
    private List<Double> voiceEmbedding;

    @Column(name = "embedding_version", length = 50)
    private String embeddingVersion;

    @Column(name = "voice_consented_at")
    private LocalDateTime voiceConsentedAt;

    public static User create() {
        return new User();
    }

    public void withdraw(LocalDateTime withdrawnAt) {
        markDeleted(withdrawnAt);
        clearVoiceProfile();
        this.voiceConsentedAt = null;
    }

    public LocalDateTime consentToVoiceCollection(LocalDateTime consentedAt) {
        if (this.voiceConsentedAt == null) {
            this.voiceConsentedAt = consentedAt;
        }
        return this.voiceConsentedAt;
    }

    public boolean hasVoiceConsent() {
        return voiceConsentedAt != null;
    }

    public void registerVoiceProfile(String voiceSampleKey, List<Double> voiceEmbedding, String embeddingVersion) {
        this.voiceSampleKey = voiceSampleKey;
        this.voiceEmbedding = List.copyOf(voiceEmbedding);
        this.embeddingVersion = embeddingVersion;
    }

    public boolean hasVoiceProfile() {
        return voiceEmbedding != null;
    }

    private void clearVoiceProfile() {
        this.voiceSampleKey = null;
        this.voiceEmbedding = null;
        this.embeddingVersion = null;
    }

    public void reactivate() {
        restoreDeleted();
    }

    public boolean isWithdrawn() {
        return getDeletedAt() != null;
    }
}
