import {parse} from 'https://deno.land/std@0.119.0/flags/mod.ts';
import * as yaml from 'https://deno.land/x/js_yaml_port/js-yaml.js';

const h_flags = parse(Deno.args, {
	string: ['format'],
	default: {
		format: 'json',
	},
});

const si_format = h_flags.format;



const H_RESPONSE_CODES = {
	400: {
		description: 'Invalid input',
	},
	403: {
		description: 'User not authorized',
	},
	404: {
		description: 'Resource not found',
	},
	412: {
		description: 'Precondition failed',
	},
};


const H_CONTENT_TURTLE = {
	'text/turtle': {
		schema: {
			type: 'string',
			example: '<https://example.org/#subject> <https://example.org/#predicate> <https://example.org/#object> .',
		},
	},
};

const H_CONTENT_SPARQL_QUERY = {
	'application/sparql-query': {
		schema: {
			type: 'string',
		},
	},
};

const H_CONTENT_SPARQL_UPDATE = {
	'application/sparql-update': {
		schema: {
			type: 'string',
		},
	},
};

const G_RESPONSE_GRAPH = {
	content: {
		...H_CONTENT_TURTLE,
	},
};

const g_components = {
	securitySchemas: {
		bearerAuth: {
			type: 'http',
			scheme: 'bearer',
			bearerFormat: 'JWT',
		},
	},
	responses: {
		HeadResource: {
			200: {},
			...H_RESPONSE_CODES,
		},
		GetResource: {
			200: G_RESPONSE_GRAPH,
			...H_RESPONSE_CODES,
		},
		Turtle: G_RESPONSE_GRAPH,
	},
};


const head_get = (si_operation: string) => ({
	head: {
		responses: {
			$ref: '#/components/responses/HeadResource',
		},
	},
	get: {
		operationId: si_operation,
		responses: {
			$ref: '#/components/responses/GetResource',
		},
	},
});

const g_sparql_query = {
	requestBody: {
		description: 'SPARQL 1.1 query string',
		required: true,
		content: {
			'application/sparql-query': {},
		},
	},

	responses: {
		$ref: '#/components/responses/GetResource',
	},
};

const g_sparql_update = {
	requestBody: {
		description: 'SPARQL 1.1 update string',
		required: true,
		content: {
			'application/sparql-update': {},
		},
	},

	responses: {
		$ref: '#/components/responses/GetResource',
	},
};

const g_rdf_turtle = {
	requestBody: {
		description: 'RDF graph content as Turtle',
		required: true,
		content: {
			...H_CONTENT_TURTLE,
		},
	},

	responses: {
		$ref: '#/components/responses/GetResource',
	},
};

const h_paths = {
	'/orgs': {
		...head_get('readAllOrgs'),

		'/{orgId}': {
			...head_get('readOrg'),

			'/repos': {
				...head_get('readAllRepos'),

				'/{repoId}': {
					...head_get('readRepo'),

					'/branches': {
						...head_get('readAllBranches'),

						'/{branchId}': {
							...head_get('readBranch'),

							patch: {
								operationId: 'updateBranch',
							},

							'/graph': {
								...head_get('readModel'),

								post: {
									operationId: 'loadModel',
									...g_sparql_query,
								},
							},

							'/query': {
								post: {
									operationId: 'queryModel',
									summary: 'Query the model at the HEAD of a branch',
									...g_sparql_query,
								},
							},

							'/update': {
								post: {
									operationId: 'commitModel',
									...g_sparql_update,
								},
							},
						},
					},

					'/locks': {
						...head_get('readAllLocks'),

						'/{lockId}': {
							...head_get('readLock'),

							put: {
								operationId: 'createLock',
								...g_sparql_update,
							},

							'/query': {
								post: {
									operationId: 'queryLock',
									summary: 'Query the model under the commit pointed to by the given lock',
									...g_sparql_query,
								},
							},
						},
					},

					'/diff': {
						post: {
							operationId: 'createDiff',
							...g_sparql_query,
						},

						'/query': {
							post: {
								operationId: 'queryDiff',
								summary: 'Query the given diff',
								...g_sparql_query,
							},
						},
					},

					'/query': {
						post: {
							operationId: 'queryRepo',
							summary: 'Query the metadata graph for the given repository',
							...g_sparql_query,
						},
					},
				},
			},

			'/collections': {
				'/{collectionId}': {
					put: {
						operationId: 'createCollection',
						...g_rdf_turtle,
					},
				},
			},
		},
	},

	'/policies': {
		'/{policyId}': {
			put: {
				operationId: 'createPolicy',
				...g_rdf_turtle,
			},
		},
	},

	'/groups': {
		'/{groupId}': {
			put: {
				operationId: 'createGroup',
				...g_rdf_turtle,
			},
		},
	},
};

function flatten_paths(h_paths_nested, h_root={}, s_path='') {
	const h_out = {};

	for(const [si_key, z_value] of Object.entries(h_paths_nested)) {
		if('/' === si_key[0]) {
			h_root[s_path+si_key] = flatten_paths(z_value, h_root, s_path+si_key);
		}
		else {
			h_out[si_key] = z_value;
		}
	}

	return s_path? h_out: h_root;
}

const g_spec = {
	openapi: '3.1.0',
	info: {
		title: 'MMS5 Layer 1 Service',
		description: 'OpenAPI specification for layer 1',
		license: {
			name: 'Apache 2.0',
			url: 'https://www.apache.org/licenses/LICENSE-2.0.html',
		},
		version: '1.0.0',
	},
	paths: flatten_paths(h_paths),
	components: g_components,
	security: [
		{
			bearerAuth: [],
		},
	],
};

if('yaml' === si_format) {
	console.log(yaml.dump(g_spec));
}
else {
	console.log(JSON.stringify(g_spec, null, '\t'));
}
