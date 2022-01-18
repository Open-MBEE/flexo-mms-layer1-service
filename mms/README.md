# MMS 5 Layer 1 Service

In the MMS 5 architecture, Layer 1 is the only service capable of direct communication with Layer 0 (the RDF quadstore). The APIs this service exposes are therefore the lowest-level access a typical MMS user or application can interact with. 

## Table of Contents
 - [API Summary](#api-summary)
 - [Contributing](#contributing)

## API Summary
This service exposes the following set of APIs:
 - [CRUD on Cluster Objects](#crud-on-cluster-objects)
 - [Virtual Endpoints](#virtual-endpoints)
     - [Querying Models](#querying-models)
     - [Updating Models](#updating-models)
 - [Analytical Querying](#analytical-querying)


### CRUD on Cluster Objects
An API for creating, reading, updating, and deleting the following objects:
 - Orgs
     - `/orgs/{orgId}`
 - Repos
     - `/orgs/{orgId}/repos/{repoId}`
 - Branches
     - `/orgs/{orgId}/repos/{repoId}/branches/{branchId}`
 - Locks
     - `/orgs/{orgId}/repos/{repoId}/locks/{lockId}`
 - Collections
     - `/orgs/{orgId}/collections/{collectionId}`
 - Users
     - `/users/{userId}`
 - Groups
     - `/groups/{groupId}`
 - Policies
     - `/policies/{policId}`
 - ...

For each of the above objects:
 - `PUT` -- **Creates** a new object.
 - `GET` -- **Reads** public properties of the given object.
 - `PATCH` -- **Updates** public properties of the given object.
 - `DELETE` -- **Deletes** the given object.

Additionally, if no object id is supplied, the server accepts:
 - `GET` -- Enumerates all objects along with their public properties.



### Virtual Endpoints
A virtual SPARQL endpoint is exposed for each `branch` and `lock` that exists within a `repo`.

#### Querying Models
SPARQL Query endpoint for reading from a model:
 - Branches
     - `/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query`
 - Locks
     - `/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query`


#### Updating Models
SPARQL Update endpoint for writing a new commit to a model:
 - Branches
     - `/orgs/{orgId}/repos/{repoId}/branches/{branchId}/update`
 - ~~Locks~~ - Locks are readonly


### Analytical Querying
The primary use case for analytical queries is to query commit logs. This gives users and applications the ability to filter by date range, by commit author, etc.

 - Repos
     - `/orgs/{orgId}/repos/{repoId}/query`
 - ...


## Contributing
See the [README](../README.md) in the parent directory for setting up a local dev environment.
