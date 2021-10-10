@file:Suppress("LocalVariableName", "VariableNaming")

package doist.ffs.routes

import doist.ffs.capturingLastInsertId
import doist.ffs.withDatabase
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.features.NotFoundException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveParameters
import io.ktor.response.header
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.delete
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.put
import io.ktor.routing.routing
import io.ktor.util.getValue
import kotlinx.serialization.ExperimentalSerializationApi

fun Application.projectRoutes() {
    routing {
        routeCreateProject()
        routeGetProjects()
        routeGetProject()
        routeUpdateProject()
        routeDeleteProject()
    }
}

const val PATH_PROJECTS = "/projects"
const val PATH_PROJECT = "/project/{id}"

/**
 * Create a new project.
 *
 * On success, responds `201 Created` with an empty body.
 *
 * | Parameter         | Required | Description               |
 * | ----------------- | -------- | ------------------------- |
 * | `organization_id` | Yes      | ID of the organization.   |
 * | `name`            | Yes      | Name of the project.      |
 */
fun Route.routeCreateProject() = post(PATH_PROJECTS) {
    val params = call.receiveParameters()
    val organization_id: Long by params
    val name: String by params
    val id = withDatabase { db ->
        db.capturingLastInsertId {
            db.projectQueries.insert(organization_id = organization_id, name = name)
        }
    }
    call.run {
        response.header(HttpHeaders.Location, PATH_PROJECT.replace("{id}", id.toString()))
        respond(HttpStatusCode.Created)
    }
}

/**
 * Lists existing projects for the organization.
 *
 * On success, responds `200 OK` with a JSON array containing all projects for the organization.
 *
 * | Parameter         | Required | Description               |
 * | ----------------- | -------- | ------------------------- |
 * | `organization_id` | Yes      | ID of the organization.   |
 */
fun Route.routeGetProjects() = get(PATH_PROJECTS) {
    val organization_id: Long by call.request.queryParameters
    val projects = withDatabase { db ->
        db.projectQueries.selectByOrganization(organization_id).executeAsList()
    }
    call.respond(HttpStatusCode.OK, projects)
}

/**
 * Get an existing project.
 *
 * On success, responds `200 OK` with a JSON object for the project.
 *
 * | Parameter | Required | Description        |
 * | --------- | -------- | ------------------ |
 * | `id`      | Yes      | ID of the project. |
 */
@OptIn(ExperimentalSerializationApi::class)
fun Route.routeGetProject() = get(PATH_PROJECT) {
    val id: Long by call.parameters
    val organization = withDatabase { db ->
        db.projectQueries.select(id = id).executeAsOneOrNull()
    } ?: throw NotFoundException()
    call.respond(HttpStatusCode.OK, organization)
}

/**
 * Update a project.
 *
 * On success, responds `200 OK` with an empty body.
 *
 * | Parameter | Required | Description          |
 * | --------- | -------- | -------------------- |
 * | `id`      | Yes      | ID of the project.   |
 * | `name`    | No       | Name of the project. |
 */
fun Route.routeUpdateProject() = put(PATH_PROJECT) {
    val id: Long by call.parameters
    val name: String? by call.receiveParameters()
    withDatabase { db ->
        val project = db.projectQueries.select(id = id).executeAsOneOrNull()
            ?: throw NotFoundException()
        db.projectQueries.update(id = id, name = name ?: project.name)
    }
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Delete a project.
 *
 * On success, responds `201 Created` with an empty body.
 *
 * | Parameter | Required | Description          |
 * | --------- | -------- | -------------------- |
 * | `id`      | Yes      | ID of the porject.   |
 */
fun Route.routeDeleteProject() = delete(PATH_PROJECT) {
    val id: Long by call.parameters
    withDatabase { db ->
        db.projectQueries.delete(id = id)
    }
    call.respond(HttpStatusCode.NoContent)
}
