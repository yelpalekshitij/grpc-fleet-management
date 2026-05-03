package com.fleetmanagement.document.seeder

import com.fleetmanagement.proto.document.documentMetadata
import com.fleetmanagement.document.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DataSeeder(private val storage: DocumentStorage) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        seedDocuments()
        log.info("Seed data loaded: 3 documents")
    }

    private fun seedDocuments() {

        // ── Document 1 — Vehicle Registration (veh-seed-001) ────────────────
        storage.save(
            documentMetadata {
                filename   = "vehicle_registration_veh-seed-001.pdf"
                mimeType   = "application/pdf"
                entityType = "vehicle"
                entityId   = "veh-seed-001"
                tags.addAll(listOf("registration", "legal", "2022"))
                customAttributes["issuing_authority"] = "Kraftfahrt-Bundesamt"
                customAttributes["valid_until"]       = "2027-03-14"
                customAttributes["vehicle_class"]     = "N1"
            },
            pdfContent("""
                VEHICLE REGISTRATION CERTIFICATE
                =================================
                Vehicle ID   : veh-seed-001
                Make / Model : Toyota Land Cruiser
                Year         : 2022
                License Plate: B-FL-001
                Color        : White
                Owner        : Fleet Management GmbH
                Address      : Potsdamer Str. 1, 10785 Berlin, DE
                Registered   : 2022-03-15
                Valid Until  : 2027-03-14
                Issuer       : Kraftfahrt-Bundesamt (KBA)
            """.trimIndent())
        )

        // ── Document 2 — Driver License (drv-seed-001, Hans Müller) ─────────
        storage.save(
            documentMetadata {
                filename   = "driver_license_drv-seed-001.pdf"
                mimeType   = "application/pdf"
                entityType = "driver"
                entityId   = "drv-seed-001"
                tags.addAll(listOf("license", "legal", "ADR"))
                customAttributes["license_class"]    = "CE"
                customAttributes["issuing_country"]  = "DE"
                customAttributes["valid_until"]      = "2029-06-30"
            },
            pdfContent("""
                DRIVER LICENSE
                ==============
                Driver ID    : drv-seed-001
                Full Name    : Hans Müller
                Date of Birth: 1985-04-22
                License No   : DE-2019-HM001
                Class        : CE (Heavy goods vehicle with trailer)
                Certifications: ADR, HGV, FORKLIFT
                Issued       : 2019-06-30
                Valid Until  : 2029-06-30
                Issuer       : Straßenverkehrsamt Berlin
            """.trimIndent())
        )

        // ── Document 3 — Insurance Certificate (veh-seed-002) ───────────────
        storage.save(
            documentMetadata {
                filename   = "insurance_certificate_veh-seed-002.pdf"
                mimeType   = "application/pdf"
                entityType = "vehicle"
                entityId   = "veh-seed-002"
                tags.addAll(listOf("insurance", "legal", "comprehensive"))
                customAttributes["policy_number"] = "POL-2024-HH-77821"
                customAttributes["insurer"]       = "Allianz SE"
                customAttributes["coverage"]      = "comprehensive"
                customAttributes["valid_until"]   = "2025-07-19"
            },
            pdfContent("""
                INSURANCE CERTIFICATE
                =====================
                Vehicle ID   : veh-seed-002
                Make / Model : Mercedes-Benz Sprinter 316
                License Plate: HH-FL-002
                Policy Number: POL-2024-HH-77821
                Insurer      : Allianz SE
                Coverage     : Comprehensive (Vollkasko)
                Premium      : EUR 1,840/year
                Valid From   : 2024-07-20
                Valid Until  : 2025-07-19
                Policyholder : Fleet Management GmbH
            """.trimIndent())
        )
    }

    private fun pdfContent(text: String): ByteArray =
        "%PDF-1.4 seed-document\n$text".toByteArray(Charsets.UTF_8)
}
