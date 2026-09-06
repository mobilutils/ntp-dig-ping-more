package io.github.mobilutils.ntp_dig_ping_more

import io.github.mobilutils.ntp_dig_ping_more.settings.ManagedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates that [ShowMDMConfigurationsViewModel] and [ManagedConfig] stay in 100% sync
 * with app_restrictions.xml.
 */
class AppRestrictionsXmlSchemaTest {

    @Test
    fun `allRestrictionsInXmlAreMappedInViewModel`() {
        // Resolve app_restrictions.xml path from project root or module
        val possiblePaths = listOf(
            File("src/main/res/xml/app_restrictions.xml"),
            File("app/src/main/res/xml/app_restrictions.xml"),
            File("../app/src/main/res/xml/app_restrictions.xml"),
        )
        val xmlFile = possiblePaths.firstOrNull { it.exists() }
        assertNotNull("app_restrictions.xml must exist in project", xmlFile)

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile!!)

        val restrictionNodes = doc.getElementsByTagName("restriction")
        val xmlKeys = mutableListOf<String>()
        val xmlTypes = mutableMapOf<String, String>()

        for (i in 0 until restrictionNodes.length) {
            val node = restrictionNodes.item(i)
            val attributes = node.attributes

            val key = attributes.getNamedItem("android:key")?.nodeValue
                ?: attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "key")?.nodeValue
            val type = attributes.getNamedItem("android:restrictionType")?.nodeValue
                ?: attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "restrictionType")?.nodeValue

            assertNotNull("Each restriction must have a key", key)
            assertNotNull("Each restriction must have a type", type)

            xmlKeys.add(key!!)
            xmlTypes[key] = type!!
        }

        // 14 restrictions defined in app_restrictions.xml
        assertEquals(14, xmlKeys.size)

        // Verify mapped fields in ViewModel
        val mappedFields = ShowMDMConfigurationsViewModel.mapConfigToFields(ManagedConfig())
        assertEquals(14, mappedFields.size)

        val mappedKeys = mappedFields.map { it.key }
        assertEquals("Mapped keys must match XML keys in order", xmlKeys, mappedKeys)

        // Verify data types match
        for (field in mappedFields) {
            val expectedType = xmlTypes[field.key]
            assertEquals("Data type for ${field.key} must match XML schema", expectedType, field.type)
        }
    }
}
