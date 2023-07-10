# MMS 5 Layer 1

[![CircleCI](https://circleci.com/gh/Open-MBEE/mms5-layer1-service.svg?style=shield)](https://circleci.com/gh/Open-MBEE/mms5-layer1-service)

This project is currently under development. This document describes how to set up a local dev environment.

## Quickstart

See https://mms5-deployment-guide.readthedocs.io/en/latest/

## Setting up local dev environment

You will need to run a local quadstore. There are a few other complementary services that help with authentication and uploading RDF data, but they are not strictly required (i.e., the stack _can_ operate without them, but may require special configuration or in some cases may impact performance).


### Run the service set

Therefore, the simplest way to get started is to stand up the prescribed service set:

```bash
docker-compose -f src/test/resources/docker-compose.yml up -d
```

Apache Jena's Fuseki quadstore will bind locally on port 3030. Should you want to issue SPARQL queries directly against the quadstore itself, Fuseki exposes the following HTTP APIs by default:

| Endpoint                          | Purpose                                                                            |
| --------------------------------- | ---------------------------------------------------------------------------------- |
| `http://localhost:3030/ds/sparql` | [SPARQL 1.1 Query](https://www.w3.org/TR/sparql11-query/)                          |
| `http://localhost:3030/ds/update` | [SPARQL 1.1 Update](https://www.w3.org/TR/sparql11-update/)                        |
| `http://localhost:3030/ds/data`   | [SPARQL 1.1 Graph Store Protocol](https://www.w3.org/TR/sparql11-http-rdf-update/) |


### Generate the initialization file

The next step is to populate the quadstore with configuration data since MMS5 stores all of its state information in the quadstore alongside user data.

This configuration data is unique to your deployment.

[comment]: <> (and should be generated in a production environment. However, for development purposes, you can skip the steps below and instead use the pregenerated `src/test/resources/cluster.trig` file. )

To generate a new deployment configuration:
```bash
cd deploy
npx ts-node src/main.ts $APP_URL > ../src/test/resources/cluster.trig
```

Where `$APP_URL` is the root URL for where the MMS5 Layer 1 instance is deployed, e.g., `https://mms5.openmbee.org/`. For local development, we simply use the stand-in `http://layer1-service`.


### Apply the initialization file

Once the initialization file has been generated at `src/test/resources/cluster.trig`, this file will automatically be used when running tests. Otherwise, make sure to apply this file to your empty quadstore (for example, by using its Graph Store Protocol API) before using MMS5.


### Deploy the MMS5 Layer 1 Application

Make sure the following environment variables are set when running the application. If running tests, make sure these variables are set in the test configuration. These will be picked up by `src/main/resources/application.conf.*` which you can also configure for further customization. 

```shell
MMS5_ROOT_CONTEXT=http://layer1-service
MMS5_QUERY_URL=http://localhost:3030/ds/sparql
MMS5_UPDATE_URL=http://localhost:3030/ds/update
MMS5_GRAPH_STORE_PROTOCOL_URL=http://localhost:3030/ds/data
```

[comment]: <> (This repo provides some scripts under [`service/`]&#40;./service&#41; that will setup a Blazegraph docker container for you and preload it with an initialization graph.)

[comment]: <> (```bash)

[comment]: <> (cd service)

[comment]: <> (./start.sh)

[comment]: <> (```)

[comment]: <> (> Re-run `start.sh` to restart the Blazegraph container and reset all its data.)

[comment]: <> (Confirm the quadstore is online by opening http://localhost:8081/bigdata/#query .)

[comment]: <> (The source code for the service is under [`mms/`]&#40;./mms&#41;. You will need to set the following environment variables in the run configuration for the Kotlin project &#40;method varies depending on IDE&#41;.)



[comment]: <> (**Example Environment variables:**)

[comment]: <> (```shell)

[comment]: <> (# if using the default blazegraph docker container scripts in `service/`:)

[comment]: <> (MMS5_STORE_QUERY=http://localhost:8081/bigdata/namespace/kb/sparql)

[comment]: <> (MMS5_STORE_UPDATE=http://localhost:8081/bigdata/namespace/kb/sparql)

[comment]: <> (```)

[comment]: <> (Run the project and send a test request thru curl to verify the service is online:)

[comment]: <> (```shell)

[comment]: <> (curl http://localhost:8080/)

[comment]: <> (```)


[comment]: <> (## Inspecting the graph)

[comment]: <> (Blazegraphs built-in SPARQL query interface is not the friendliest, so developers may prefer another SPARQL tool. There is a large ecosystem of SPARQL tooling available for authoring queries and visualizing the results. For now, you can try out [YASGUI]&#40;https://github.com/TriplyDB/Yasgui&#41; &#40;for easy setup, try using [this docker image]&#40;https://hub.docker.com/r/erikap/yasgui&#41;&#41;. More detailed instructions to come.)


[comment]: <> (## Testing the APIs)

[comment]: <> (We are using [Postman]&#40;https://www.postman.com/&#41; to document, generate and submit HTTP requests to the service for development and testing. An exported Postman collection file can be found here: [resource/crud.postman_collection.json]&#40;resource/crud.postman_collection.json&#41; ; import this file into your Postman application to get started.)
