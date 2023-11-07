#!/bin/bash
N_FLEXO_MMS_BG_PORT=8081
S_FLEXO_MMS_BG_HOST=localhost
P_FLEXO_MMS_BG_REST="http://$S_FLEXO_MMS_BG_HOST:$N_FLEXO_MMS_BG_PORT/bigdata"
P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE="mms5-blazegraph.properties"

XTL_TIMEOUT=15

function wait_for() {
	P_URI=$1

	if ! command -v wget &> /dev/null; then
		echo "The 'wget' command must be installed"
		exit 1
	fi

	while :; do
		XT_EXPIRE=$(($(date +%s) + $XTL_TIMEOUT))

		echo "Trying endpoint $P_URI..."

		wget --timeout=1 -q "$P_URI" -O /dev/null > /dev/null 2>&1

		n_result=$?

		if [[ $n_result -eq 0 ]] ; then
			break
		fi

		if [[ $(date +%s) -ge $XT_EXPIRE ]]; then
			echo "Operation timed out" >&2
			exit 1
		fi

		sleep 1
	done
}

# launch blazegraph store
docker run --name mms5-store -d \
	-p "$N_FLEXO_MMS_BG_PORT:8080" \
	-v "$PWD/$P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE:/$P_FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE" \
	-v "$PWD/data:/data" \
	lyrasis/blazegraph:2.1.5

# wait
wait_for "$P_FLEXO_MMS_BG_REST"
echo "Success. Blazegraph is online."
sleep 2

echo "Loading quads..."

# make request to dataloader
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

echo -e "\n"
echo "Finished loading quads. Verifying data..."

# rename goods and fails
pushd ./data/clean/
	for sr_file in ./*.good; do
		echo "Successfully loaded ${sr_file%.*}"

		mv -i "$sr_file" "${sr_file%.*}";
	done

	for sr_file in ./*.fail; do
		echo "Failed to parse and load ${sr_file%*.}"

		mv -i "$sr_file" "${sr_file%.*}";
	done
popd

# query for number of triples
S_RESPONSE=$(curl "$P_FLEXO_MMS_BG_REST/namespace/kb/sparql" \
	-H 'Accept: application/sparql-results+json' \
	-H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
	--data-raw 'query=select+(count(*)+as+%3Fcount)+from+%3Chttps%3A%2F%2Fdemo.openmbee.org%2Fmms%2Fprojects%2FDemo%2Fsnapshots%2FModel.e4a1c%3E+%7B+%3Fs+%3Fp+%3Fo+%7D')

if ! command -v jq &> /dev/null; then
	echo "$S_RESPONSE"
else
	N_QUADS=$(echo "$S_RESPONSE" | jq '.results.bindings[0].count.value')
	echo "Complete. $N_QUADS quads loaded."
fi
