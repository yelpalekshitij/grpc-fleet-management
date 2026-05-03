package com.fleetmanagement.document.grpc

import com.fleetmanagement.document.storage.DocumentStorage
import com.fleetmanagement.proto.document.DeleteDocumentRequest
import com.fleetmanagement.proto.document.DeleteDocumentResponse
import com.fleetmanagement.proto.document.DocumentMetadata
import com.fleetmanagement.proto.document.DocumentServiceGrpcKt
import com.fleetmanagement.proto.document.DownloadChunk
import com.fleetmanagement.proto.document.DownloadRequest
import com.fleetmanagement.proto.document.GetDocumentMetadataRequest
import com.fleetmanagement.proto.document.ListDocumentsRequest
import com.fleetmanagement.proto.document.ListDocumentsResponse
import com.fleetmanagement.proto.document.UploadChunk
import com.fleetmanagement.proto.document.deleteDocumentResponse
import com.fleetmanagement.proto.document.downloadChunk
import com.fleetmanagement.proto.document.listDocumentsResponse
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.io.ByteArrayOutputStream

// =============================================================================
// DOCUMENT gRPC SERVICE — FILE TRANSFER VIA BYTES
//
// Answers the question: "Can gRPC transfer files?"
// YES — using the bytes type and streaming RPCs.
//
// WHY STREAMING?
// gRPC has a default 4MB message size limit. A file can be 100MB+.
// Solution: split the file into small chunks (e.g. 64KB each) and stream them.
//
// PATTERN:
//   Upload (client-streaming):
//     Message 1 → oneof.metadata  (filename, mimeType, tags, etc.)
//     Message 2 → oneof.content   (bytes chunk 1)
//     Message 3 → oneof.content   (bytes chunk 2)
//     ...
//     Server collects all chunks, assembles, stores, returns final metadata.
//
//   Download (server-streaming):
//     Client sends one DownloadRequest with document_id
//     Server streams: metadata first, then byte chunks
// =============================================================================

private const val CHUNK_SIZE = 64 * 1024  // 64 KB per chunk

@GrpcService
class DocumentGrpcService(
    private val storage: DocumentStorage,
    @Value("\${fleet.storage.max-file-size-bytes}") private val maxFileSizeBytes: Long
) : DocumentServiceGrpcKt.DocumentServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(DocumentGrpcService::class.java)

    // =========================================================================
    // UNARY RPC — GetDocumentMetadata
    // =========================================================================
    override suspend fun getDocumentMetadata(request: GetDocumentMetadataRequest): DocumentMetadata {
        return storage.findById(request.documentId)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription("Document '${request.documentId}' not found")
            )
    }

    // =========================================================================
    // UNARY RPC — ListDocuments
    // =========================================================================
    override suspend fun listDocuments(request: ListDocumentsRequest): ListDocumentsResponse {
        val docs = storage.findByEntity(request.entityType, request.entityId)
        return listDocumentsResponse {
            documents.addAll(docs)
        }
    }

    // =========================================================================
    // UNARY RPC — DeleteDocument
    // =========================================================================
    override suspend fun deleteDocument(request: DeleteDocumentRequest): DeleteDocumentResponse {
        val deleted = storage.delete(request.documentId)
        return deleteDocumentResponse {
            success = deleted
            message = if (deleted) "Document deleted" else "Document not found"
        }
    }

    // =========================================================================
    // CLIENT-STREAMING RPC — UploadDocument
    //
    // The client streams UploadChunk messages. Each chunk uses `oneof` to be
    // EITHER metadata (first message) OR bytes (subsequent messages).
    //
    // We use Flow<UploadChunk> and collect() to process each message as it arrives.
    // When the client closes its side of the stream, collect() completes.
    //
    // The bytes type in protobuf maps to:
    //   Java: com.google.protobuf.ByteString
    //   Kotlin: ByteString (same, accessed via .toByteArray())
    // =========================================================================
    override suspend fun uploadDocument(requests: Flow<UploadChunk>): DocumentMetadata {
        var receivedMetadata: DocumentMetadata? = null
        val contentBuffer = ByteArrayOutputStream()
        var chunkCount = 0

        requests.collect { chunk ->
            // oneof: the chunk is EITHER metadata OR content, never both
            when (chunk.dataCase) {
                UploadChunk.DataCase.METADATA -> {
                    // First message must be metadata
                    if (receivedMetadata != null) {
                        throw StatusException(
                            Status.INVALID_ARGUMENT.withDescription("Metadata already received")
                        )
                    }
                    receivedMetadata = chunk.metadata
                    log.info("Upload started: filename='{}', mimeType='{}'",
                        chunk.metadata.filename, chunk.metadata.mimeType)
                }

                UploadChunk.DataCase.CONTENT -> {
                    // bytes — raw file content chunk
                    if (receivedMetadata == null) {
                        throw StatusException(
                            Status.INVALID_ARGUMENT.withDescription("Metadata must be sent first")
                        )
                    }

                    val bytes = chunk.content.toByteArray()  // ByteString → ByteArray
                    contentBuffer.write(bytes)
                    chunkCount++

                    if (contentBuffer.size() > maxFileSizeBytes) {
                        throw StatusException(
                            Status.RESOURCE_EXHAUSTED.withDescription(
                                "File exceeds max size of ${maxFileSizeBytes / 1024 / 1024}MB"
                            )
                        )
                    }
                }

                else -> throw StatusException(
                    Status.INVALID_ARGUMENT.withDescription("Unknown chunk type")
                )
            }
        }

        val meta = receivedMetadata
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("No metadata received"))

        val content = contentBuffer.toByteArray()
        log.info("Upload complete: {} bytes in {} chunks", content.size, chunkCount)

        return storage.save(meta, content)
    }

    // =========================================================================
    // SERVER-STREAMING RPC — DownloadDocument
    //
    // Client sends one request; server streams back multiple DownloadChunk messages.
    //
    // The server splits the file into CHUNK_SIZE (64KB) pieces and emits each one.
    // Using emit() in a flow { } builder sends each chunk to the client.
    //
    // For a 5MB file this would produce ~80 chunks.
    // =========================================================================
    override fun downloadDocument(request: DownloadRequest): Flow<DownloadChunk> = flow {
        val doc = storage.load(request.documentId)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription("Document '${request.documentId}' not found")
            )

        // First chunk: send metadata so client knows filename, size, mime type
        emit(
            downloadChunk {
                metadata = doc.metadata  // DocumentMetadata (no bytes here)
            }
        )
        log.info("Download started: '{}' ({} bytes)", doc.metadata.filename, doc.content.size)

        // Subsequent chunks: stream the raw file bytes in CHUNK_SIZE pieces
        var offset = 0
        var chunkNum = 0
        while (offset < doc.content.size) {
            val end = minOf(offset + CHUNK_SIZE, doc.content.size)
            val slice = doc.content.copyOfRange(offset, end)

            emit(
                downloadChunk {
                    // ByteString.copyFrom() converts ByteArray → protobuf bytes
                    content = ByteString.copyFrom(slice)
                }
            )

            offset = end
            chunkNum++
        }

        log.info("Download complete: '{}' — {} chunks sent", doc.metadata.filename, chunkNum)
    }
}
