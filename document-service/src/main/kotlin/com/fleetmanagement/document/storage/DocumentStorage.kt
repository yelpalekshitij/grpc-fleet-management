package com.fleetmanagement.document.storage

import com.fleetmanagement.proto.document.DocumentMetadata
import com.fleetmanagement.proto.document.documentMetadata
import com.google.protobuf.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StoredDocument(val metadata: DocumentMetadata, val content: ByteArray)

@Component
class DocumentStorage(
    @Value("\${fleet.storage.base-path}") private val basePath: String
) {
    private val log = LoggerFactory.getLogger(DocumentStorage::class.java)
    private val index = ConcurrentHashMap<String, DocumentMetadata>()

    init {
        File(basePath).mkdirs()
        log.info("Document storage initialized at: $basePath")
    }

    fun save(meta: DocumentMetadata, content: ByteArray): DocumentMetadata {
        val id = UUID.randomUUID().toString()
        val now = nowTimestamp()

        val stored = meta.toBuilder()
            .setId(id)
            .setSizeBytes(content.size.toLong())  // int64
            .setCreatedAt(now)
            .setUpdatedAt(now)
            .build()

        val file = File("$basePath/$id")
        file.writeBytes(content)

        index[id] = stored
        log.info("Saved document '{}' ({} bytes, mime={})", stored.filename, content.size, stored.mimeType)
        return stored
    }

    fun load(id: String): StoredDocument? {
        val meta = index[id] ?: return null
        val file = File("$basePath/$id")
        if (!file.exists()) return null
        return StoredDocument(meta, file.readBytes())
    }

    fun findById(id: String): DocumentMetadata? = index[id]

    fun findByEntity(entityType: String, entityId: String): List<DocumentMetadata> =
        index.values.filter {
            (entityType.isBlank() || it.entityType == entityType) &&
            (entityId.isBlank() || it.entityId == entityId)
        }

    fun delete(id: String): Boolean {
        val removed = index.remove(id) != null
        if (removed) File("$basePath/$id").delete()
        return removed
    }

    private fun nowTimestamp(): Timestamp {
        val now = Instant.now()
        return Timestamp.newBuilder().setSeconds(now.epochSecond).setNanos(now.nano).build()
    }
}
