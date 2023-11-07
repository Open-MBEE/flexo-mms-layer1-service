#!/bin/bash
N_FLEXO_MMS_BG_PORT=8080
S_FLEXO_MMS_BG_HOST=localhost
P_FLEXO_MMS_BG_REST="http://$S_FLEXO_MMS_BG_HOST:$N_FLEXO_MMS_BG_PORT/bigdata"
P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE="mms5-blazegraph.properties"
curl -X POST "$P_FLEXO_MMS_BG_REST/dataloader" \
	-H 'Content-Type: text/plain' \
	--data-binary @- <<- EOF
		namespace=kb
		propertyFile=/$P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE
		fileOrDirs=/data/clean
		defaultGraph=https://demo.openmbee.org/mms/projects/Demo/snapshots/Model.e4a1c
		-format=trig
		quiet=false
		verbose=0
		closure=false
		durableQueues=true
	EOF
