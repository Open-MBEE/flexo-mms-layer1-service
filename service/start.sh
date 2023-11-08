#!/bin/bash

# (re)start
./stop.sh

mkdir -p ./data

# find data files
function find_ttls() {
	A_TTLS=$(find ./data -type f -name '*.ttl')
}

function find_bz2s() {
	A_BZ2S=$(find ./data -type f -name '*.ttl.bz2')
}

# find ttl files
find_ttls
B_TTLS=$(echo -n "$A_TTLS" | wc -c)

# no ttl files
if [[ $B_TTLS -eq 0 ]]; then
	# find bz2 files
	find_bz2s
	B_BZ2S=$(echo -n "$A_BZ2S" | wc -c)

	# no ttl.bz2 files
	if [[ $B_BZ2S -eq 0 ]]; then
		# prepare query
		cat <<- EOF > ./data/query.sparql
			PREFIX dataid: <http://dataid.dbpedia.org/ns/core#>
			PREFIX dct: <http://purl.org/dc/terms/>
			PREFIX dcat:  <http://www.w3.org/ns/dcat#>

			SELECT DISTINCT ?file  WHERE {
				?dataset dataid:version <https://databus.dbpedia.org/marvin/mappings/geo-coordinates-mappingbased/2019.09.01> .
				?dataset dcat:distribution ?distribution .
				?distribution dcat:downloadURL ?file .
				?distribution dataid:contentVariant ?cv .
				FILTER ( str(?cv) = 'en' )
			}
		EOF

		# download dataset from dbpedia
		docker run --name flexo-mms-download \
			--rm \
			-e FORMAT="ttl" \
			-v "$PWD/data/query.sparql:/opt/databus-client/query.sparql" \
			-v "$PWD/data/repo:/var/repo" \
			dbpedia/databus-client

		# update bz2 files list
		find_bz2s
	fi

	# extract bz2 files
	echo "$A_BZ2S" | xargs bzip2 -d

	# update ttl files list
	find_ttls
fi

# # print flattened list of ttl files
# echo "$A_TTLS" | xargs echo "loading ttl files:"

# # no dummy file
# P_DUMMY="./data/clean/dummy.ttl"
# if [[ -e "$P_DUMMY" ]]; then
# 	# first ttl file
# 	P_TTL=$(echo "$A_TTLS" | sed -n '1p')

# 	# replace dummy ttl file
# 	mkdir -p ./data/clean
# 	cp -f "$P_TTL" "$P_DUMMY"
# fi

# confirmation
echo "Selected dummy ttl file for loading: $P_DUMMY"

# triplestore
./blazegraph.sh
