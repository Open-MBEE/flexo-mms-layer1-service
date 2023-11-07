# MMS-5 Layer 0: Database Initialization Generator

Generates a TriG file with necessary object and access control definitions for a new Flexo MMS deployment.

### Setup

```sh
yarn install
```

### Build

Determine the URL that Flexo MMS will be served from. This gets used when building the vocabulary in order to produce dereferenceable IRIs.

Assign this URL to the `ROOT_CONTEXT` environment variable. For local testing, you can use the following placeholder:
```sh
export ROOT_CONTEXT="http://layer1-service"
```

Then, build the cluster TriG file using:

```sh
yarn build $ROOT_CONTEXT
```

OR

```sh
mkdir build
npx ts-node src/main.ts $ROOT_CONTEXT > build/cluster.trig
```
