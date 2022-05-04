# MMS5 Cluster Graph Configruation Generator

## Building

Set the root context URL for the MMS deployment. This should reflect the actual URL of your deployed service. For example:
```shell
ROOT_CONTEXT="https://your-app-domain/mms5"
```

```shell
yarn build $ROOT_CONTEXT
```

Output file will be in `build/cluster.trig`
