# MMS-5 Layer 0: Database Initialization Generator

Generates a TriG file with necessary object and access control definitions for a new MMS5 deployment.

### Build

```sh
mkdir build
npx ts-node src/main.ts $ROOT_CONTEXT > build/cluster.trig
```
