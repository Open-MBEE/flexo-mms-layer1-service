MMS 5 Layer 1 Service
=====================

This service provides the main api for MMS 5. It will need a SPARQL 1.1 compliant quadstore as a backend.

Other supporting MMS 5 services such as the Auth and Load service can be used to provide authentication and large model loads. Please see their respective documentation on matching configurations.

Quadstore Configs
--------------------------

  PORT
    Port to run on

    | `Default: 8080`

  MMS5_QUERY_URL
    Quadstore sparql endpoint for read queries

    | `Default: https://quad-store-domain-ro/sparql`

  MMS5_UPDATE_URL
    Quadstore sparql endpoint for updates

    | `Default: https://quad-store-domain/sparql`

  MMS5_GRAPH_STORE_PROTOCOL_URL
    Quadstore GSP endpoint

    | `Default: https://quad-store-domain/sparql/gsp`

MMS 5 Services Configs
-----------------------

  MMS5_LOAD_SERVICE_URL
    Optional, If using the load service give url here (if not given, load operations will use quadstore GSP). Url should be in the form <Load service domain and port>/store

  JWT_DOMAIN
    This should be the same as what's configured for MMS5 Auth Service

    | `Default: https://jwt-provider-domain/`

  JWT_AUDIENCE
    This should be the same as what's configured for MMS5 Auth Service

    | `Default: jwt-audience`

  JWT_REALM
    This should be the same as what's configured for MMS5 Auth Service

    | `Default: MMS5 Microservices`

  JWT_SECRET
    This should be the same as what's configured for MMS5 Auth Service

    | `Default: test1234`

Other Configs
---------------------

  MMS5_ROOT_CONTEXT
    This should be the same context url as given to the initialization script.

    | `Default: http://layer1-service`

  MMS5_GLOMAR_RESPONSE
    If true, respond with 404 to unauthorized requests, even when a resource exists (neither confirming nor denying its existence)

    | `Default: true`

  MMS5_MAXIMUM_LITERAL_SIZE_KIB
    Large patch strings above this threshold size will not be stored in the triplestore. if unset, no limit on literal size enforced

    | `Default: 61440`

  MMS5_GZIP_LITERALS_LARGER_THAN_KIB
    Patch strings above this threshold size will be gzipped when stored in the triplestore. if unset, will not attempt to gzip any strings

    | `Default: 512`

  MMS5_SPARQL_REQUEST_TIMEOUT
    Timeout per request sent to the triplestore, in seconds.

    | `Default: 1800`

Generate the initialization file
--------------------------------

The quadstore needs to be initialized with configuration data before it can be used. This data is unique to a deployment and needs a context.

To generate the init file, checkout https://github.com/Open-MBEE/mms5-layer1-service and do the following:

.. code:: bash

   cd deploy
   npx ts-node src/main.ts $APP_URL > cluster.trig

Where ``$APP_URL`` is the root URL for where the MMS5 Layer 1 instance is deployed, e.g., ``https://mms5.openmbee.org``. For local development, we simply use the stand-in ``http://layer1-service``.

This context is also important in the configuration of other MMS5 services like the Auth service - the context needs to match.

If there is a specific user or group that should be the cluster admin, edit the trig file so their iri is part of the super admins policy. See MMS5 Auth Service documentation for more details.

Apply the initialization file
-----------------------------

Once the initialization file has been generated at ``cluster.trig``, apply this file to your empty quadstore (for example, by using its Graph Store Protocol API to insert the data) before using MMS5.

MMS 5 API
---------

See API documentation at https://www.openmbee.org/mms5-layer1-openapi/, generated from https://github.com/Open-MBEE/mms5-layer1-openapi
