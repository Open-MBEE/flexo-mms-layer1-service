# MMS 5 Layer 1

This project is currently under development. This document describes how to set up a local dev environment.

## Setting up local dev environment

You will need to run a local quadstore. This repo provides some scripts under [`service/`](./service) that will setup a Blazegraph docker container for you and preload it with an initialization graph.

```bash
cd service
./start.sh
```

> Re-run `start.sh` to restart the Blazegraph container and reset all its data.

Confirm the quadstore is online by opening http://localhost:8081/bigdata/#query .

The source code for the service is under [`mms/`](./mms). You will need to set the following environment variables in the run configuration for the Kotlin project (method varies depending on IDE).

**Example Environment variables:**
```shell
# if using the default blazegraph docker container scripts in `service/`:
MMS5_STORE_QUERY=http://localhost:8081/bigdata/namespace/kb/sparql
MMS5_STORE_UPDATE=http://localhost:8081/bigdata/namespace/kb/sparql
```

Run the project and send a test request thru curl to verify the service is online:

```shell
curl http://localhost:8080/
```


## Inspecting the graph

Blazegraphs built-in SPARQL query interface is not the friendliest, so developers may prefer another SPARQL tool. There is a large ecosystem of SPARQL tooling available for authoring queries and visualizing the results. For now, you can try out [YASGUI](https://github.com/TriplyDB/Yasgui) (for easy setup, try using [this docker image](https://hub.docker.com/r/erikap/yasgui)). More detailed instructions to come.


## Testing the APIs

We are using [Postman](https://www.postman.com/) to document, generate and submit HTTP requests to the service for development and testing. An exported Postman collection file can be found here: [resource/crud.postman_collection.json](resource/crud.postman_collection.json) ; import this file into your Postman application to get started.
