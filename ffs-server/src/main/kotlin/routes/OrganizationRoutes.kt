package doist.ffs.routes

import doist.ffs.auth.Permission
import doist.ffs.auth.UserPrincipal
import doist.ffs.db.RoleEnum
import doist.ffs.db.capturingLastInsertId
import doist.ffs.db.organizations
import doist.ffs.db.roles
import doist.ffs.ext.authorizeForOrganization
import doist.ffs.ext.authorizeForUser
import doist.ffs.ext.optionalRoute
import doist.ffs.plugins.database
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import routes.PATH_LATEST

const val PATH_ORGANIZATIONS = "/organizations"

@Suppress("FunctionName")
fun PATH_ORGANIZATION(id: Any) = "$PATH_ORGANIZATIONS/$id"

fun Application.installOrganizationRoutes() = routing {
    optionalRoute(PATH_LATEST) {
        route(PATH_ORGANIZATIONS) {
            authenticate("session") {
                createOrganization()
                getOrganizations()
                getOrganization()
                updateOrganization()
                deleteOrganization()
            }

            route("/{id}/$PATH_USERS/{user_id}") {
                authenticate("session") {
                    addUser()
                    updateUser()
                    removeUser()
                }
            }
        }
    }
}

/**
 * Create a new organization. The requesting user becomes an admin.
 */
private fun Route.createOrganization() = post {
    val userId = call.principal<UserPrincipal>()!!.id
    val name = call.receiveParameters().getOrFail("name")

    val id = database.run {
        transactionWithResult<Long> {
            capturingLastInsertId {
                organizations.insert(name)
            }.also {
                roles.insert(user_id = userId, organization_id = it, role = RoleEnum.ADMIN)
            }
        }
    }

    call.response.header(HttpHeaders.Location, PATH_ORGANIZATION(id))
    call.respond(HttpStatusCode.Created)
}

/**
 * Lists organizations for the current user.
 */
private fun Route.getOrganizations() = get {
    val userId = call.principal<UserPrincipal>()!!.id

    authorizeForUser(id = userId)

    val organizations = database.roles.selectOrganizationByUser(user_id = userId).executeAsList()
    call.respond(HttpStatusCode.OK, organizations)
}

/**
 * Get an existing organization.
 */
private fun Route.getOrganization() = get("{id}") {
    val id = call.parameters.getOrFail<Long>("id")

    authorizeForOrganization(id, Permission.READ)

    val organization = database.organizations.select(id = id).executeAsOneOrNull()
        ?: throw NotFoundException()
    call.respond(HttpStatusCode.OK, organization)
}

/**
 * Update an organization.
 */
private fun Route.updateOrganization() = put("{id}") {
    val id = call.parameters.getOrFail<Long>("id")
    val params = call.receiveParameters()
    val name = params["name"]

    authorizeForOrganization(id, Permission.WRITE)

    database.organizations.run {
        val organization = select(id = id).executeAsOneOrNull() ?: throw NotFoundException()
        update(id = id, name = name ?: organization.name)
    }
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Delete an organization.
 */
private fun Route.deleteOrganization() = delete("{id}") {
    val id = call.parameters.getOrFail<Long>("id")

    authorizeForOrganization(id, Permission.DELETE)

    database.organizations.delete(id = id)
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Add user to an organization.
 */
private fun Route.addUser() = post {
    val id = call.parameters.getOrFail<Long>("id")
    val userId = call.parameters.getOrFail<Long>("user_id")
    val params = call.receiveParameters()
    val role = RoleEnum.valueOf(params.getOrFail("role").uppercase())

    authorizeForOrganization(id, Permission.DELETE)

    database.roles.insert(user_id = userId, organization_id = id, role = role)
    call.respond(HttpStatusCode.Created)
}

/**
 * Update user role within organization.
 */
private fun Route.updateUser() = put {
    val id = call.parameters.getOrFail<Long>("id")
    val userId = call.parameters.getOrFail<Long>("user_id")
    val params = call.receiveParameters()
    val role = RoleEnum.valueOf(params.getOrFail("role").uppercase())

    authorizeForOrganization(id, Permission.DELETE)

    database.roles.update(user_id = userId, organization_id = id, role = role)
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Remove user from organization.
 */
private fun Route.removeUser() = delete {
    val id = call.parameters.getOrFail<Long>("id")
    val userId = call.parameters.getOrFail<Long>("user_id")

    authorizeForOrganization(id, Permission.DELETE)

    database.roles.delete(user_id = userId, organization_id = id)
    call.respond(HttpStatusCode.NoContent)
}
