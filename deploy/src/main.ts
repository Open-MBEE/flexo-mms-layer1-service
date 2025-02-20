// @ts-ignore
import trig_write from '@graphy/content.trig.write';
// @ts-ignore
import factory from '@graphy/core.data.factory';

const P_PREFIX = process.argv[2].replace(/\/$/, "");

if(!P_PREFIX) {
	throw new Error(`Must provide context prefix IRI as positional argument`);
}

import {
	type Hash,
	oderom,
} from './belt';


interface ClassConfig {
	super?: string;
}

function classes(h_classes: Hash<ClassConfig>) {
	return oderom(h_classes, (si_class, gc_class) => ({
		[`mms:${si_class}`]: {
			a: 'rdfs:Class',
			'rdfs:label': '"'+si_class,
			...(gc_class.super? {
				'rdfs:subClassOf': `mms:${gc_class.super}`,
			}: {}),
		},
	}));
}


interface PropertyConfig {
	domain?: string;
	range?: string;
}

function properties(h_properties: Hash<PropertyConfig>) {
	return oderom(h_properties, (si_property, gc_property) => ({
		[`mms:${si_property}`]: {
			a: 'rdf:Property',
			'rdfs:label': '"'+si_property,
			...gc_property.domain && {
				'rdfs:domain': `mms:${gc_property.domain}`,
			},
			...gc_property.range && {
				'rdfs:range': `mms:${gc_property.range}`,
			},
		},
	}));
}

interface ScopeConfig {
	implies?: string | string[];
}

function scopes(h_scopes: Hash<ScopeConfig>) {
	return oderom(h_scopes, (si_scope, gc_scope) => ({
		[`mms:${si_scope}`]: {
			a: 'rdfs:Class',
			'rdfs:label': `"${si_scope} level scope`,
			'rdfs:subClassOf': 'mms:Scope',
			...gc_scope.implies && {
				'mms:implies': [gc_scope.implies].flat().map(si => `mms:${si}`),
			},
		},
	}));
}

interface CrudConfig {
	implies?: string | string[] | ((s: string) => string | string[]),
}

interface ActionsConfig {
	Create?: CrudConfig;
	Read?: CrudConfig;
	Update?: CrudConfig;
	Delete?: CrudConfig;
}

interface PermissionConfig {
	crud: ActionsConfig,
}

const H_CRUD_DEFAULT: ActionsConfig = {
	Create: {},
	Read: {},
	Update: {
		implies: s => [`Read${s}`],
	},
	Delete: {
		implies: s => [`Update${s}`],
	},
};

function permissions(h_permissions: Hash<PermissionConfig>) {
	return oderom(h_permissions, (si_permission, gc_permission) => oderom(gc_permission.crud as Hash<CrudConfig>, (si_crud, gc_crud) => ({
			[`mms-object:Permission.${si_crud}${si_permission}`]: {
				a: 'mms:Permission',
				...gc_crud.implies && {
					'mms:implies': ['function' === typeof gc_crud.implies? gc_crud.implies(si_permission): gc_crud.implies]
						.flat().map(s => `mms-object:Permission.${s}`),
				},
			},
		}))
	);
}

interface PermitConfig {
	permits?: string | string[] | ((s: string) => string | string[]);
}

interface RolesConfig {
	Admin?: PermitConfig;
	Write?: PermitConfig;
	Read?: PermitConfig;
}

interface PermitsConfig {
	permits: PermitsConfig,
}

function roles(h_roles: Hash<RolesConfig>) {
	return oderom(h_roles, (si_role, h_role) => oderom(h_role as Hash<PermitConfig>, (si_crud, gc_crud) => ({
			[`mms-object:Role.${si_crud}${si_role}`]: {
				a: 'mms:Role',
				...gc_crud.permits && {
					'mms:permits': ['function' === typeof gc_crud.permits? gc_crud.permits(si_role): gc_crud.permits]
						.flat().map(s => `mms-object:Permission.${s}`),
				},
			},
		}))
	);
}


const H_ROLE_DEFAULT: RolesConfig = {
	Admin: {
		permits: s => [`Delete${s}`],
	},
	Write: {
		permits: s => [`Update${s}`],
	},
	Read: {
		permits: s => [`Read${s}`],
	},
}


const ds_writer = trig_write({
	prefixes: {
		rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
		rdfs: 'http://www.w3.org/2000/01/rdf-schema#',
		mms: 'https://mms.openmbee.org/rdf/ontology/',
		'mms-object': 'https://mms.openmbee.org/rdf/objects/',
		m: `${P_PREFIX}/`,
		ma: `${P_PREFIX}/access-control-scope/`,
		'm-graph': `${P_PREFIX}/graphs/`,
		'm-user': `${P_PREFIX}/users/`,
		'm-group': `${P_PREFIX}/groups/`,
		'm-policy': `${P_PREFIX}/policies/`,
	},
	style: {
		directives: 'sparql',
		graphKeyword: 'graph',
	},
})

ds_writer.pipe(process.stdout);

ds_writer.write({
	type: 'c4',
	value: {
		[factory.comment()]: 'users and groups which are the subjects of access control policies',
		'm-graph:AccessControl.Agents': {
			'm-user:root': {
				a: 'mms:User',
				'mms:id': '"root',
			},

			'm-user:admin': {
				a: 'mms:User',
				'mms:group': 'm-group:SuperAdmins',
				'mms:id': '"admin',
			},

			'm-user:anon': {
				a: 'mms:User',
				'mms:id': '"anon',
			},

			'm-group:SuperAdmins': {
				a: 'mms:Group',
				'mms:id': '"super_admins',
				'mms:etag': '"mms-init-etag-value-group-SuperAdmins',
			},
		},

		[factory.comment()]: 'default policies',
		'm-graph:AccessControl.Policies': {
			'm-policy:DefaultSuperAdmins': {
				a: 'mms:Policy',
				'mms:subject': [
					'm-user:root',
					'm-group:SuperAdmins',
				],
				'mms:scope': 'm:',
				'mms:role': [
					'AdminCluster',
					'AdminOrg',
					'AdminRepo',
					'AdminMetadata',
					'AdminModel',
					'AdminAccessControlAny',
				].map(s => `mms-object:Role.${s}`),
			},
		},

		[factory.comment()]: 'cluster-specific classes',
		'm-graph:Cluster': {
			'm:': {
				a: 'mms:Cluster',
			},

			'ma:': {
				a: 'mms:AccessControlAny',
			},
		},

		[factory.comment()]: 'copy of static schema inherited from global MMS definitions',
		'm-graph:Schema': {
			[factory.comment()]: '====================================',
			[factory.comment()]: '==            Classes             ==',
			[factory.comment()]: '====================================',
			...classes({
				Project: {},
				Collection: {
					super: 'Project',
				},
				Repo: {
					super: 'Project',
				},

				Ref: {},
				Branch: {
					super: 'Ref',
				},
				Lock: {
					super: 'Ref',
				},
				InterimLock: {
					super: 'Lock',
				},

				Snapshot: {},
				Model: {
					super: 'Snapshot',
				},
				Staging: {
					super: 'Snapshot',
				},

				Commit: {},

				Agent: {},
				User: {
					super: 'Agent',
				},
				Group: {
					super: 'Agent',
				},

				Policy: {},
			}),

			[factory.comment()]: '====================================',
			[factory.comment()]: '==           Properties           ==',
			[factory.comment()]: '====================================',
			...properties({
				ref: {
					range: 'Ref',
				},

				commit: {
					range: 'Commit',
				},

				snapshot: {
					range: 'Snapshot',
				},
			}),
		},

		'm-graph:AccessControl.Definitions': {
			[factory.comment()]: '====================================',
			[factory.comment()]: '==             Scopes             ==',
			[factory.comment()]: '====================================',
			...scopes({
				Cluster: {
					implies: [
						'Org',
						'AccessControlAny',
					],
				},
				Org: {
					implies: 'Project',
				},
				Project: {
					implies: [
						'Repo',
						'Collection',
					],
				},
				Repo: {
					implies: 'Ref',
				},
				Collection: {},
				Ref: {
					implies: [
						'Branch',
						'Lock',
					],
				},
				AccessControlAny: {
					implies: [
						'Agent',
						'Policy',
					],
				},
				Agent: {
					implies: [
						'User',
						'Group',
					],
				},
				User: {},
				Group: {},
				Policy: {
					// TODO: add subtype for each type of policy that can be CRUD'd
					// implies: []
				},
			}),

			// ...classes({
			// 	Collection: {
			// 		super: 'Project',
			// 	},
			// 	Repo: {
			// 		super: 'Project',
			// 	},

			// 	Branch: {
			// 		super: 'Ref',
			// 	},
			// 	Lock: {
			// 		super: 'Ref',
			// 	},
			// }),


			[factory.comment()]: '====================================',
			[factory.comment()]: '==   Object-Centric Permissions   ==',
			[factory.comment()]: '====================================',
			...permissions({
				Cluster: {
					crud: {
						...H_CRUD_DEFAULT,
						Update: {
							implies: [
								'ReadCluster',
								'CreateOrg',
								'CreateAccessControlAny'
							],
						},
						Delete: {
							implies: [
								'UpdateCluster',
								'DeleteOrg',
								'DeleteAccessControlAny',
							],
						},
					},
				},

				Org: {
					crud: {
						...H_CRUD_DEFAULT,
						Update: {
							implies: [
								'ReadOrg',
								'CreateProject',
							],
						},
						Delete: {
							implies: [
								'UpdateOrg',
								// ability to delete an org implies ability to delete projects in that org
								'DeleteProject',
							],
						},
					},
				},

				Project: {
					crud: {
						Create: {
							implies: ['CreateCollection', 'CreateRepo'],
						},
						Read: {
							implies: ['ReadCollection', 'ReadRepo'],
						},
						Update: {
							implies: ['ReadProject', 'UpdateCollection', 'UpdateRepo'],
						},
						Delete: {
							implies: ['UpdateProject', 'DeleteCollection', 'DeleteRepo'],
						},
					},
				},

				Collection: {
					crud: H_CRUD_DEFAULT,
				},

				Repo: {
					crud: {
						...H_CRUD_DEFAULT,
						Update: {
							implies: [
								'ReadRepo',
								'UpdateBranch',  // PATCH for updating repo metadata
								'UpdateLock',  // PATCH for updating repo metadata
							],
						},
						Delete: {
							implies: [
								'UpdateRepo',
								'CreateBranch',
								'DeleteBranch',
								'CreateLock',
								'DeleteLock',
								'CreateDiff',
								'DeleteDiff',
							],
						},
					},
				},

				Branch: {
					crud: H_CRUD_DEFAULT,
				},

				Lock: {
					crud: H_CRUD_DEFAULT,
				},

				AccessControlAny: {
					crud: {
						...H_CRUD_DEFAULT,
						Update: {
							implies: [
								'ReadAccessControlAny',
								'CreateAgent',
								'CreatePolicy',
							],
						},
						Delete: {
							implies: [
								'UpdateAccessControlAny',
								'DeleteAgent',
								'DeletePolicy',
							],
						},
					},
				},

				Agent: {
					crud: {
						...H_CRUD_DEFAULT,
						Update: {
							implies: [
								'ReadAgent',
								'CreateUser',
								'CreateGroup',
							],
						},
						Delete: {
							implies:[
								'UpdateAgent',
								'DeleteUser',
								'DeleteGroup',
							],
						},
					},
				},

				User: {
					crud: H_CRUD_DEFAULT,
				},

				Group: {
					crud: H_CRUD_DEFAULT,
				},

				Policy: {
					crud: H_CRUD_DEFAULT,
				},
			}),

			[factory.comment()]: '====================================',
			[factory.comment()]: '==             Roles              ==',
			[factory.comment()]: '====================================',
			...roles({
				Org: H_ROLE_DEFAULT,
				Repo: H_ROLE_DEFAULT,
				Model: H_ROLE_DEFAULT,
				Metadata: H_ROLE_DEFAULT,
				Cluster: H_ROLE_DEFAULT,
				AccessControlAny: H_ROLE_DEFAULT,
			}),
		},
	},
})
