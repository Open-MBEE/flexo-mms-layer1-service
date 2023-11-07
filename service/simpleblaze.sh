#!/bin/bash
N_FLEXO_MMS_BG_PORT=8081
S_FLEXO_MMS_BG_HOST=localhost
P_FLEXO_MMS_BG_REST="http://$S_FLEXO_MMS_BG_HOST:$N_FLEXO_MMS_BG_PORT/bigdata"
P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE="mms5-blazegraph.properties"
XTL_TIMEOUT=15

docker run --network=host --name mms5-store -d \
	-p "$N_FLEXO_MMS_BG_PORT:8080" \
	-v "$PWD/$P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE:/$P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE" \
	-v "$PWD/data:/data" \
	lyrasis/blazegraph:2.1.5

