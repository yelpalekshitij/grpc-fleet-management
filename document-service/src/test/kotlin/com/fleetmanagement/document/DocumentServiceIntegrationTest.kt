package com.fleetmanagement.document

import com.fleetmanagement.document.grpc.DocumentGrpcService
import com.fleetmanagement.document.storage.DocumentStorage
import com.fleetmanagement.proto.document.DocumentServiceGrpcKt
import com.fleetmanagement.proto.document.deleteDocumentRequest
import com.fleetmanagement.proto.document.documentMetadata
import com.fleetmanagement.proto.document.downloadRequest
import com.fleetmanagement.proto.document.getDocumentMetadataRequest
import com.fleetmanagement.proto.document.listDocumentsRequest
import com.fleetmanagement.proto.document.uploadChunk
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path

// =============================================================================
// DOCUMENT SERVICE — INTEGRATION TEST
//
// Tests the bytes type and all 4 RPC patterns in document-service:
//   ✓ Client-streaming: UploadDocument — metadata first, then byte chunks via oneof
//   ✓ Server-streaming: DownloadDocument — metadata first, then byte chunks
//   ✓ Bytes integrity: uploaded bytes == downloaded bytes (round-trip test)
//   ✓ Large file: verified across multiple 64KB chunks
//   ✓ oneof: UploadChunk.DataCase.METADATA vs CONTENT dispatch
//   ✓ int64: sizeBytes correctly stored and returned
//   ✓ map<string,string>: customAttributes round-trip
//   ✓ repeated string: tags round-trip
//   ✓ Unary: GetDocumentMetadata, ListDocuments, DeleteDocument
//   ✓ NOT_FOUND propagation
// =============================================================================

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DocumentServiceIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: DocumentServiceGrpcKt.DocumentServiceCoroutineStub

    private lateinit var uploadedDocId: String

    @BeforeAll
    fun startServer() {
        tempDir = Files.createTempDirectory("fleet-doc-test-")
        val serverName = InProcessServerBuilder.generateName()
        val storage = DocumentStorage(tempDir.toString())

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(DocumentGrpcService(storage, 100 * 1024 * 1024L))  // 100MB limit
            .build()
            .start()

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        stub = DocumentServiceGrpcKt.DocumentServiceCoroutineStub(channel)
    }

    @AfterAll
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    @Order(1)
    fun `uploadDocument — client-streaming with metadata + byte chunks via oneof`() { runBlocking {
        val content = "Fleet vehicle registration document content. ".repeat(100).toByteArray()
        val chunkSize = 256

        // Build the stream: first message is metadata, rest are byte chunks
        val chunks = buildList {
            // First: oneof.metadata (tells the server what the file is)
            add(uploadChunk {
                metadata = documentMetadata {
                    filename   = "vehicle-reg-001.pdf"
                    mimeType   = "application/pdf"                  // string
                    entityType = "vehicle"
                    entityId   = "veh-001"
                    tags.addAll(listOf("registration", "legal"))   // repeated string
                    customAttributes.putAll(mapOf(                 // map<string,string>
                        "year"      to "2024",
                        "authority" to "Berlin KFZ-Zulassung"
                    ))
                }
            })
            // Subsequent: oneof.content (raw bytes, split into chunks)
            content.toList().chunked(chunkSize).forEach { slice ->
                add(uploadChunk {
                    this.content = ByteString.copyFrom(slice.toByteArray())  // bytes type
                })
            }
        }

        val meta = stub.uploadDocument(flow { chunks.forEach { emit(it) } })
        uploadedDocId = meta.id

        assertThat(meta.id).isNotBlank()
        assertThat(meta.filename).isEqualTo("vehicle-reg-001.pdf")
        assertThat(meta.mimeType).isEqualTo("application/pdf")
        assertThat(meta.sizeBytes).isEqualTo(content.size.toLong())  // int64
        assertThat(meta.tagsList).containsExactlyInAnyOrder("registration", "legal")  // repeated string
        assertThat(meta.customAttributesMap).containsEntry("year", "2024")             // map<string,string>
        assertThat(meta.createdAt.seconds).isGreaterThan(0L)                           // Timestamp
    } }

    @Test
    @Order(2)
    fun `downloadDocument — server-streaming returns metadata chunk then byte chunks`() { runBlocking {
        val allChunks = stub.downloadDocument(downloadRequest { documentId = uploadedDocId }).toList()

        // First chunk is always metadata (oneof.metadata)
        val metaChunk    = allChunks.first()
        val contentChunks = allChunks.drop(1)

        assertThat(metaChunk.hasMetadata()).isTrue()
        assertThat(metaChunk.metadata.filename).isEqualTo("vehicle-reg-001.pdf")
        assertThat(metaChunk.metadata.sizeBytes).isGreaterThan(0L)

        // Remaining chunks carry raw bytes (oneof.content)
        assertThat(contentChunks).isNotEmpty()
        contentChunks.forEach { chunk ->
            assertThat(chunk.hasContent()).isTrue()
            assertThat(chunk.content.size()).isGreaterThan(0)
        }
    } }

    @Test
    @Order(3)
    fun `bytes round-trip — uploaded bytes equal downloaded bytes exactly`() { runBlocking {
        val originalContent = "This is the exact binary content we want to round-trip.".repeat(50)
            .toByteArray()

        // Upload
        val chunkSize = 128
        val meta = stub.uploadDocument(flow {
            emit(uploadChunk {
                metadata = documentMetadata {
                    filename = "roundtrip-test.bin"; mimeType = "application/octet-stream"
                    entityType = "test"; entityId = "test-001"
                }
            })
            originalContent.toList().chunked(chunkSize).forEach { slice ->
                emit(uploadChunk { content = ByteString.copyFrom(slice.toByteArray()) })
            }
        })

        // Download and reassemble
        val chunks = stub.downloadDocument(downloadRequest { documentId = meta.id }).toList()
        val downloaded = chunks.drop(1).fold(ByteArray(0)) { acc, chunk ->
            acc + chunk.content.toByteArray()  // ByteString → ByteArray
        }

        // Exact byte-for-byte equality
        assertThat(downloaded).isEqualTo(originalContent)
        assertThat(downloaded.size).isEqualTo(originalContent.size)
    } }

    @Test
    @Order(4)
    fun `large file — correctly split into multiple 64KB chunks and reassembled`() { runBlocking {
        val largeContent = ByteArray(500_000) { it.toByte() }  // ~488KB

        val meta = stub.uploadDocument(flow {
            emit(uploadChunk {
                metadata = documentMetadata {
                    filename = "large-file.bin"; mimeType = "application/octet-stream"
                    entityType = "vehicle"; entityId = "veh-large"
                }
            })
            val chunkSize = 64 * 1024  // 64KB — same as production chunk size
            var offset = 0
            while (offset < largeContent.size) {
                val end = minOf(offset + chunkSize, largeContent.size)
                emit(uploadChunk {
                    content = ByteString.copyFrom(largeContent, offset, end - offset)
                })
                offset = end
            }
        })

        assertThat(meta.sizeBytes).isEqualTo(500_000L)  // int64

        // Download and verify
        val chunks = stub.downloadDocument(downloadRequest { documentId = meta.id }).toList()
        val downloaded = chunks.drop(1).fold(ByteArray(0)) { acc, c -> acc + c.content.toByteArray() }
        assertThat(downloaded).isEqualTo(largeContent)
        assertThat(chunks.drop(1)).hasSizeGreaterThan(1)  // verified multiple chunks
    } }

    @Test
    @Order(5)
    fun `getDocumentMetadata — unary fetch returns stored metadata`() { runBlocking {
        val meta = stub.getDocumentMetadata(getDocumentMetadataRequest { documentId = uploadedDocId })

        assertThat(meta.id).isEqualTo(uploadedDocId)
        assertThat(meta.filename).isEqualTo("vehicle-reg-001.pdf")
        assertThat(meta.sizeBytes).isGreaterThan(0L)                 // int64
        assertThat(meta.entityType).isEqualTo("vehicle")
    } }

    @Test
    @Order(6)
    fun `listDocuments — filters by entityType and entityId`() { runBlocking {
        val list = stub.listDocuments(listDocumentsRequest {
            entityType = "vehicle"
            entityId   = "veh-001"
        })

        assertThat(list.documentsList).isNotEmpty()
        assertThat(list.documentsList).allMatch { it.entityType == "vehicle" }
    } }

    @Test
    @Order(7)
    fun `deleteDocument — removes document, subsequent download returns NOT_FOUND`() { runBlocking {
        val del = stub.deleteDocument(deleteDocumentRequest { documentId = uploadedDocId })
        assertThat(del.success).isTrue()

        assertThatThrownBy {
            runBlocking {
                stub.downloadDocument(downloadRequest { documentId = uploadedDocId }).toList()
            }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code)
                    .isEqualTo(io.grpc.Status.Code.NOT_FOUND)
            })
    } }
}
