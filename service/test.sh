cat \
	<<- EOF
	namespace=mms5
	propertyFile=/$FLEXO_MMS_BLAZEGRAPH_PROPERTIES_FILE
	fileOrDirs=/data
	-format=turtle
	quiet=false
	verbose=0
	closure=false
	durableQueues=true
	EOF
