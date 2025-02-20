# Contributing

## Overview

Let `{SRC}` be a substitute for `src/main/kotlin/org/openmbee/flexo/mms` throughout this document.

### 1. `{SRC}/server/Routing.kt`

A good place to start the first time diving into the codebase, calls out to routers for the various types of objects such as Orgs, Repos, Branches, etc.

### 2. `{SRC}/routes/*.kt`

Each type of object is represented by a file having the same name here, e.g., Org and Repo map to `{SRC}/routes/Org.kt` and `{SRC}/routes/Repo.kt`, respectively.

These files define how routing should be handled for the given set of call paths and all available call methods associated with each object. For example, Orgs handle the call the paths `/orgs` and `/orgs/{orgId}`, which we simply refer to as _endpoints_. The `{SRC}/routes/Org.kt` defines how to route calls to each of those two endpoints depending on the method, e.g., `GET`, `PUT`, `DELETE`, etc.

In the Layer 1 service, each endpoint implements a specific protocol, which will be one of the following:
 - [Linked Data Platform Direct Container](https://www.w3.org/TR/ldp/#ldpdc) (LDP-DC)
 - [SPARQL 1.1 Graph Store Protocol](https://www.w3.org/TR/sparql11-http-rdf-update/)
 - [SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/) (either query or update operation)

Each endpoint router invokes a method associated with one of these protocols, i.e., `linkedDataPlatformDirectContainer()`, `graphStoreProtocol()`, `sparqlQuery()`, or `sparqlUpdate()`.

### 3. `{SRC}/routes/server/{PROTOCOL}.kt`

Where `{PROTOCOL}` is one of:
 - `LinkedDataPlatform.kt`
 - `GraphStore.kt`
 - `SparqlQuery.kt`
 - `SparqlUpdate.kt`

These files handle all request processing and response building associated with those protocols. 

#### Request and Response Contexts

To facilitate downstream methods with processing call-specific data, each of these protocols creates instances that extend the `GenericRequest` and `GenericResponse` classes. The purposes of these classes is to provide a common abstraction to incoming calls and outgoing responses where method-specific data can be attached.

For example, the class `SparqlQueryRequest` stores a normalized data object associated with an incoming request for a SPARQL query endpoint, no matter whether the request came through the `GET` or `POST` route. This allows downstream methods to operate on the call's request object itself rather than the details of the HTTP request.

Similarly, classes are also defined for response contexts which may depend on the request method or headers. For example, `LdpDirectContainerRequest` is used for binding data associated with any incoming request to an LDP-DC entity, and `LdpMutateResponse` is used for binding data associated with any action that causes the mutation of an LDP resource. So a request to `PUT /org/open-mbee` would use these aforementioned request and response context types.

### 4. `{SRC}/Layer1Context.kt`

Encapsulates both the request and response contexts, providing properties and methods relevant to any route handler. Accepts two generic arguments, `TRequestContext` (e.g., `SparqlQueryRequest`, `LdpDirectContainerRequest`, and so on) and `TResponseContext` (e.g., `LdpMutateResponse`).

### 5. `{SRC}/routes/ldp/*.kt`, `{SRC}/routes/gsp/*.kt`, `{SRC}/routes/sparql/*.kt`

These handlers operate on bounded Layer1Context objects (i.e., where a specific or generic Request and Response context are defined). Typically, their purpose is to build SPARQL strings and interact with the underlying "Layer 0" quadstore before passing control and data back to the protocol-specific methods.

For example, when performing a request to `GET /orgs`, the handler declaration takes on the signature `fun Layer1Context<LdpDirectContainerRequest, LdpGetResponse>.getOrgs()`, localizing the request and response contexts (modified here for readability). This allows the handler method to access properties and methods associated with GET'ing an LDP-DC resource, as well as constructing an appropriate response.













