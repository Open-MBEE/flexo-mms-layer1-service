# MMS5 Cluster Graphs Configruation Generator

MMS5 requires the underlying quadstore (layer 0) to follow a rigid database schema complete with schema definitions for Access Control and Version Control objects.

This tool generates a cluster-specific initialization dataset in the form of a TriG file which should loaded into the new quadstore before any transactions take place.

## Building

Set the root context URL for the MMS deployment. This should reflect the actual URL of your deployed service. For example:
```shell
ROOT_CONTEXT="https://your-app-domain/path-to-mms5"
```

```shell
yarn build $ROOT_CONTEXT
```

Output file will be in `build/cluster.trig`
