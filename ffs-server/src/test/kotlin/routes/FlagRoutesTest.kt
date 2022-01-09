package doist.ffs.routes

import doist.ffs.auth.Permission
import doist.ffs.db.Flag
import doist.ffs.db.TokenGenerator
import doist.ffs.db.capturingLastInsertId
import doist.ffs.db.flags
import doist.ffs.db.organizations
import doist.ffs.db.projects
import doist.ffs.db.tokens
import doist.ffs.env.ENV_INTERNAL_ROLLOUT_ID
import doist.ffs.ext.handleSse
import doist.ffs.module
import doist.ffs.plugins.database
import doist.ffs.serialization.json
import doist.ffs.sse.SSE_FIELD_PREFIX_DATA
import doist.ffs.sse.SSE_FIELD_PREFIX_ID
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.AuthScheme
import io.ktor.http.encodeURLPath
import io.ktor.server.application.Application
import io.ktor.server.testing.withTestApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class FlagRoutesTest {
    @Test
    fun testFlagCreate() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        assertResourceCreates(
            uri = PATH_FLAGS,
            args = listOf("project_id" to projectId.toString(), "name" to NAME, "rule" to RULE_TRUE)
        )
        val projects = application.database.flags.selectByProject(projectId).executeAsList()
        assert(projects.size == 1)
        assert(projects[0].name == NAME)
        assert(projects[0].rule == RULE_TRUE)
    }

    @Test
    fun testFlagCreateLocation() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        val location = assertResourceCreates(
            uri = PATH_FLAGS,
            args = listOf("project_id" to projectId.toString(), "name" to NAME, "rule" to RULE_TRUE)
        )
        assertResource<Flag>(location)
    }

    @Test
    fun testFlagCount() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        setupToken(application, projectId, TOKEN_READ)
        assertResourceCount<Flag>(uri = PATH_FLAGS, token = TOKEN_READ, count = 0)
        application.database.flags.insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
        assertResourceCount<Flag>(uri = PATH_FLAGS, token = TOKEN_READ, count = 1)
    }

    @Test
    fun testFlagRead() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        setupToken(application, projectId, TOKEN_READ)
        val id = application.database.capturingLastInsertId {
            flags.insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
        }
        assertResource<Flag>(uri = PATH_FLAG(id)) { flag ->
            assert(flag.id == id)
            assert(flag.project_id == projectId)
            assert(flag.name == NAME)
            assert(flag.rule == RULE_TRUE)
        }
    }

    @Test
    fun testFlagSse(): Unit = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        setupToken(application, projectId, TOKEN_READ)
        handleSse(
            uri = PATH_FLAGS,
            setup = {
                addHeader(HttpHeaders.Authorization, "${AuthScheme.Bearer} $TOKEN_READ")
            }
        ) { channel ->
            val readLine = suspend { channel.readUTF8Line()!! }

            // Channel starts out empty as there is no data.
            assert(channel.availableForRead == 0)

            // Create a flag and check it is sent.
            var flag = application.database.run {
                flags.run {
                    val id = capturingLastInsertId {
                        insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
                    }
                    select(id).executeAsOne()
                }
            }
            readLine().startsWith(SSE_FIELD_PREFIX_ID)
            readLine().let { line ->
                line.startsWith(SSE_FIELD_PREFIX_DATA)
                val flags = json.decodeFromString<List<Flag>>(
                    line.substring(SSE_FIELD_PREFIX_DATA.length)
                )
                assert(flags == listOf(flag))
            }
            readLine().isEmpty()

            // Update the flag and check it is sent again.
            flag = application.database.flags.run {
                update(id = flag.id, name = NAME_UPDATED, rule = RULE_FALSE)
                select(flag.id).executeAsOne()
            }
            readLine().startsWith(SSE_FIELD_PREFIX_ID)
            readLine().let { line ->
                line.startsWith(SSE_FIELD_PREFIX_DATA)
                val flags = json.decodeFromString<List<Flag>>(
                    line.substring(SSE_FIELD_PREFIX_DATA.length)
                )
                assert(flags == listOf(flag))
            }
            readLine().isEmpty()

            // Archive the flag and check it is sent again.
            flag = application.database.flags.run {
                archive(id = flag.id)
                select(flag.id).executeAsOne()
            }
            readLine().startsWith(SSE_FIELD_PREFIX_ID)
            readLine().let { line ->
                line.startsWith(SSE_FIELD_PREFIX_DATA)
                val flags = json.decodeFromString<List<Flag>>(
                    line.substring(SSE_FIELD_PREFIX_DATA.length)
                )
                assert(flags == listOf(flag))
            }
            readLine().isEmpty()

            // Without further changes, channel is empty.
            assert(channel.availableForRead == 0)
        }
    }

    @Test
    fun testFlagUpdate() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        val id = application.database.capturingLastInsertId {
            flags.insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
        }
        assertResourceUpdates(
            uri = PATH_FLAG(id),
            args = listOf("name" to NAME_UPDATED)
        )
        var project = application.database.flags.select(id).executeAsOne()
        assert(project.id == id)
        assert(project.project_id == projectId)
        assert(project.name == NAME_UPDATED)
        assert(project.rule == RULE_TRUE)
        assertResourceUpdates(
            uri = PATH_FLAG(id),
            args = listOf("rule" to RULE_FALSE)
        )
        project = application.database.flags.select(id).executeAsOne()
        assert(project.id == id)
        assert(project.project_id == projectId)
        assert(project.name == NAME_UPDATED)
        assert(project.rule == RULE_FALSE)
        assertResourceUpdates(
            uri = PATH_FLAG(id),
            args = listOf("name" to NAME, "rule" to RULE_TRUE)
        )
        project = application.database.flags.select(id).executeAsOne()
        assert(project.id == id)
        assert(project.project_id == projectId)
        assert(project.name == NAME)
        assert(project.rule == RULE_TRUE)
    }

    @Test
    fun testFlagArchive() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        var flag = application.database.run {
            flags.run {
                val id = capturingLastInsertId {
                    insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
                }
                select(id).executeAsOne()
            }
        }
        assert(flag.archived_at == null)
        assertResourceUpdates(uri = "${PATH_FLAG(flag.id)}$PATH_ARCHIVE", args = emptyList())
        flag = application.database.flags.select(flag.id).executeAsOne()
        assert(flag.archived_at != null)
    }

    @Test
    fun testFlagUnarchive() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        var flag = application.database.run {
            flags.run {
                val id = capturingLastInsertId {
                    insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
                }
                archive(id)
                select(id).executeAsOne()
            }
        }
        assert(flag.archived_at != null)
        assertResourceDeletes(uri = "${PATH_FLAG(flag.id)}$PATH_ARCHIVE")
        flag = application.database.flags.select(flag.id).executeAsOne()
        assert(flag.archived_at == null)
    }

    @Test
    fun testFlagEvalRead() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        setupToken(application, projectId, TOKEN_EVAL)
        val path = "$PATH_FLAGS$PATH_EVAL?env=${json.encodeToString(ENV).encodeURLPath()}"

        assertResource<Map<String, Boolean>>(
            uri = path,
            token = TOKEN_EVAL
        ) { map ->
            assert(map.isEmpty())
        }

        val flag = application.database.run {
            flags.run {
                val id = capturingLastInsertId {
                    insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
                }
                select(id).executeAsOne()
            }
        }
        assertResource<Map<String, Boolean>>(
            uri = path,
            token = TOKEN_EVAL
        ) { map ->
            assert(map.size == 1)
            val (name, enabled) = map.entries.single()
            assert(name == NAME)
            assert(enabled)
        }

        application.database.flags.run {
            update(id = flag.id, name = NAME, rule = RULE_FALSE)
            select(flag.id).executeAsOne()
        }
        assertResource<Map<String, Boolean>>(
            uri = path,
            token = TOKEN_EVAL
        ) { map ->
            assert(map.size == 1)
            val (name, enabled) = map.entries.single()
            assert(name == NAME)
            assert(!enabled)
        }
    }

    @Test
    fun testFlagEvalSse(): Unit = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        setupToken(application, projectId, TOKEN_EVAL)
        val path = "$PATH_FLAGS$PATH_EVAL?env=${json.encodeToString(ENV).encodeURLPath()}"
        handleSse(
            uri = path,
            setup = {
                addHeader(HttpHeaders.Authorization, "${AuthScheme.Bearer} $TOKEN_EVAL")
            }
        ) { channel ->
            val readLine = suspend { channel.readUTF8Line()!! }

            // Channel starts out empty as there is no data.
            assert(channel.availableForRead == 0)

            // Create a flag and check its eval is sent.
            val flag = application.database.run {
                flags.run {
                    val id = capturingLastInsertId {
                        insert(project_id = projectId, name = NAME, rule = RULE_TRUE)
                    }
                    select(id).executeAsOne()
                }
            }
            readLine().startsWith(SSE_FIELD_PREFIX_ID)
            readLine().let { line ->
                line.startsWith(SSE_FIELD_PREFIX_DATA)
                val flagEvals = json.decodeFromString<Map<String, Boolean>>(
                    line.substring(SSE_FIELD_PREFIX_DATA.length)
                )
                assert(flagEvals == mapOf(NAME to true))
            }
            readLine().isEmpty()

            // Update the flag rule and check its eval is sent again.
            application.database.flags.run {
                update(id = flag.id, name = NAME, rule = RULE_FALSE)
                select(flag.id).executeAsOne()
            }
            readLine().startsWith(SSE_FIELD_PREFIX_ID)
            readLine().let { line ->
                line.startsWith(SSE_FIELD_PREFIX_DATA)
                val flagEvals = json.decodeFromString<Map<String, Boolean>>(
                    line.substring(SSE_FIELD_PREFIX_DATA.length)
                )
                assert(flagEvals == mapOf(NAME to false))
            }
            readLine().isEmpty()

            // Update the flag name and ensure the channel remains empty.
            application.database.flags.run {
                update(id = flag.id, name = NAME_UPDATED, rule = RULE_FALSE)
                select(flag.id).executeAsOne()
            }
            assert(channel.availableForRead == 0)
        }
    }

    @Test
    fun testFlagReadAuthenticate() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        assertStatus(uri = PATH_FLAGS, status = HttpStatusCode.Unauthorized)
        assertStatus(uri = PATH_FLAGS, token = TOKEN_READ, status = HttpStatusCode.Unauthorized)
        setupToken(application, projectId, TOKEN_EVAL)
        assertStatus(uri = PATH_FLAGS, token = TOKEN_EVAL, status = HttpStatusCode.Unauthorized)
        setupToken(application, projectId, TOKEN_READ)
        assertStatus(uri = PATH_FLAGS, token = TOKEN_READ, status = HttpStatusCode.OK)
    }

    @Test
    fun testFlagEvalAuthenticate() = withTestApplication(Application::module) {
        val projectId = setupProject(application)
        val path = "$PATH_FLAGS$PATH_EVAL?env=${json.encodeToString(ENV).encodeURLPath()}"
        assertStatus(uri = path, status = HttpStatusCode.Unauthorized)
        assertStatus(uri = path, token = TOKEN_EVAL, status = HttpStatusCode.Unauthorized)
        setupToken(application, projectId, TOKEN_READ)
        assertStatus(uri = path, token = TOKEN_READ, status = HttpStatusCode.Unauthorized)
        setupToken(application, projectId, TOKEN_EVAL)
        assertStatus(uri = path, token = TOKEN_EVAL, status = HttpStatusCode.OK)
    }

    companion object {
        private const val NAME = "test-flag"
        private const val NAME_UPDATED = "new-test-flag"
        private const val RULE_TRUE = "gt(env[\"number\"], 2)"
        private const val RULE_FALSE = "lt(env[\"number\"], 2)"
        private val ENV = buildJsonObject {
            put(ENV_INTERNAL_ROLLOUT_ID, "123456789abcdef")
            put("number", 3)
        }

        private val TOKEN_EVAL = TokenGenerator.generate(Permission.EVAL)
        private val TOKEN_READ = TokenGenerator.generate(Permission.READ)

        private fun setupProject(application: Application): Long {
            val organizationId = application.database.capturingLastInsertId {
                organizations.insert(name = "test-organization")
            }
            return application.database.capturingLastInsertId {
                projects.insert(organization_id = organizationId, name = "test-project")
            }
        }

        private fun setupToken(application: Application, projectId: Long, token: String) {
            application.database.tokens.insert(
                token = token,
                project_id = projectId,
                description = "test token",
            )
        }
    }
}
