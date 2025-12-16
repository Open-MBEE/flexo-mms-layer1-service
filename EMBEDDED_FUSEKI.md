# Flexo MMS Layer1 with Embedded Fuseki

This document describes how to build and run Flexo MMS Layer1 with an embedded Apache Jena Fuseki server in a single JAR file.

## Building the Fat JAR

To build the fat JAR that includes both Layer1 and Fuseki:

```bash
./gradlew fatJar
```

This will create: `build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar`

## Running the Combined JAR

### Basic Usage

Run the JAR with default settings (in-memory dataset):

```bash
java -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```

This will:
- Start Fuseki on port 3030 with dataset name `/ds`
- Use an in-memory dataset (data is not persisted)
- Automatically load the embedded `cluster.trig` initialization file
- Start the Layer1 service on port 31337

### Configuration via Environment Variables

You can customize the behavior using environment variables:

```bash
# Fuseki Configuration
export FUSEKI_PORT=3030                      # Port for Fuseki server (default: 3030)
export FUSEKI_DATASET_NAME=ds                # Dataset name (default: ds)
export FUSEKI_PERSISTENCE_MODE=memory        # Options: memory, tdb2, persistent (default: memory)
export FUSEKI_TDB_LOCATION=./fuseki-data     # Directory for TDB2 storage (only used if persistence mode is tdb2/persistent)

# Layer1 Configuration
export FLEXO_MMS_LAYER1_PORT=31337           # Port for Layer1 service (default: 31337)
export FLEXO_MMS_ROOT_CONTEXT=http://localhost:31337  # Root context for Layer1 (default: http://localhost:31337)

# Note: You can also use FLEXO_LAYER1_PORT, LAYER1_PORT or PORT environment variables for Layer1 port
# Precedence: FLEXO_MMS_LAYER1_PORT > FLEXO_LAYER1_PORT > LAYER1_PORT > PORT

# JWT Configuration (optional)
export JWT_DOMAIN=https://your-jwt-provider/
export JWT_AUDIENCE=your-audience
export JWT_REALM="Flexo MMS"
export JWT_SECRET=your-secret

java -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```

### Using Persistent Storage

To use persistent TDB2 storage instead of in-memory:

```bash
FUSEKI_PERSISTENCE_MODE=tdb2 FUSEKI_TDB_LOCATION=./my-data java -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```

This will store data in the `./my-data` directory, which will persist between restarts.

### External cluster.trig File

If you want to use a custom initialization file instead of the embedded one:

1. Place your `cluster.trig` file in the current working directory
2. Run the JAR as usual

The launcher will use the external file if it exists, otherwise it will use the embedded version.

To generate a custom `cluster.trig`:

```bash
cd deploy
npm install
npx ts-node src/main.ts "http://your-deployment-url" > ../cluster.trig
```

## Endpoints

Once running, the following endpoints are available:

### Fuseki Endpoints

- SPARQL Query: `http://localhost:3030/ds/sparql`
- SPARQL Update: `http://localhost:3030/ds/update`
- Graph Store Protocol: `http://localhost:3030/ds/data`
- Fuseki UI: `http://localhost:3030/`

### Layer1 Endpoints

- API: `http://localhost:31337/` (see main README for API documentation)

## Advantages of the Embedded Approach

1. **Single Deployment Unit**: One JAR file contains everything needed
2. **Simplified Setup**: No need to configure external Fuseki server
3. **Automatic Configuration**: Layer1 is pre-configured to connect to the embedded Fuseki
4. **Portable**: Easy to move between environments
5. **Development Friendly**: Quick startup for development and testing

## System Requirements

- Java 17 or later
- Minimum 512MB RAM (1GB+ recommended for production)
- Disk space for persistent storage (if using TDB2 mode)

## Troubleshooting

### Out of Memory Errors

Increase Java heap size:

```bash
java -Xmx2g -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```

### Port Already in Use

Change the Fuseki or Layer1 port:

```bash
# Change Fuseki port to 3031
FUSEKI_PORT=3031 java -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar

# Change Layer1 port to 8080
FLEXO_MMS_LAYER1_PORT=8080 java -jar build/libs/org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```

### Initialization Data Not Loading

Check the console output for error messages. If the embedded `cluster.trig` is missing, you can:

1. Rebuild the JAR (which will regenerate cluster.trig)
2. Provide an external cluster.trig file in the working directory

## Architecture

The `EmbeddedFusekiLauncher` class orchestrates the startup:

1. Parses configuration from environment variables
2. Creates and starts an embedded Fuseki server
3. Loads initialization data (cluster.trig) into Fuseki
4. Configures system properties for Layer1 to connect to Fuseki
5. Starts the Layer1 Ktor application

Both services run in the same JVM process, sharing the same lifecycle.

## Production Deployment

For production deployments, consider:

1. Use persistent storage: `FUSEKI_PERSISTENCE_MODE=tdb2`
2. Configure appropriate heap size based on dataset size
3. Set up proper JWT authentication
4. Use a reverse proxy (nginx, Apache) for SSL/TLS termination
5. Configure monitoring and logging
6. Regular backups of the TDB2 directory (if using persistent mode)

Example production startup:

```bash
FUSEKI_PERSISTENCE_MODE=tdb2 \
FUSEKI_TDB_LOCATION=/var/lib/flexo-mms/data \
FLEXO_MMS_LAYER1_PORT=443 \
FUSEKI_PORT=3030 \
FLEXO_MMS_ROOT_CONTEXT=https://mms.example.com \
JWT_DOMAIN=https://auth.example.com/ \
JWT_AUDIENCE=flexo-mms \
java -Xmx4g -Xms2g -jar org.openmbee.flexo.mms.layer1-0.2.0-with-fuseki.jar
```
