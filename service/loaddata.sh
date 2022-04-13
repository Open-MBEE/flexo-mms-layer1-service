#!/bin/bash
N_MMS5_BG_PORT=8080
S_MMS5_BG_HOST=localhost
P_MMS5_BG_REST="http://$S_MMS5_BG_HOST:$N_MMS5_BG_PORT/bigdata"
P_MMS5_BLAZEGRAPH_PROPERTIES_FILE="mms5-blazegraph.properties"
curl -X POST "$P_MMS5_BG_REST/dataloader" \
	-H 'Content-Type: text/plain' \
	--data-binary @- <<- EOF
		namespace=kb
		propertyFile=/$P_MMS5_BLAZEGRAPH_PROPERTIES_FILE
		fileOrDirs=/data/clean
		defaultGraph=https://demo.openmbee.org/mms/projects/Demo/snapshots/Model.e4a1c
		-format=trig
		quiet=false
		verbose=0
		closure=false
		durableQueues=true
	EOF
