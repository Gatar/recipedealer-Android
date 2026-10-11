package com.gatar.recipedealer

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File

/** Paths of the repository, independent of the test working directory. */
object TestFiles {
    val root = File(System.getProperty("projectDir") ?: "..")
    val updateGolden = System.getProperty("updateGolden") == "true"
    fun repo(path: String) = File(root, path)
    fun resource(path: String) = repo("app/src/test/resources/$path")
    fun fixture(name: String) = repo("app/src/androidTest/assets/fixtures/$name")
}

/** Validates JSON against schema/capture-v1.json (formats asserted). */
object CaptureSchema {
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        TestFiles.repo("schema/capture-v1.json").readText(),
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build(),
    )

    fun errors(json: String): List<String> = schema.validate(mapper.readTree(json)).map { it.message }
}
