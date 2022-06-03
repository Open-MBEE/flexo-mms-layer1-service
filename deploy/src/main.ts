// @ts-ignore
import trig_write from '@graphy/content.trig.write';
// @ts-ignore
import factory from '@graphy/core.data.factory';

const P_PREFIX = process.argv[2];

if(!P_PREFIX) {
	throw new Error(`Must provide a prefix IRI as positional argument`);
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
	implies?: string;
}

function scopes(h_scopes: Hash<ScopeConfig>) {
	return oderom(h_scopes, (si_scope, gc_scope) => ({
		[`mms:${si_scope}`]: {
			a: 'rdfs:Class',
			'rdfs:label': `"${si_scope} level scope`,
			'rdfs:subClassOf': 'mms:Scope',
			...gc_scope.implies && {
				'mms:implies': `mms:${gc_scope.implies}`,
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
		m: P_PREFIX,
		'm-graph': `${P_PREFIX}graphs/`,
		'm-user': `${P_PREFIX}users/`,
		'm-group': `${P_PREFIX}groups/`,
		'm-policy': `${P_PREFIX}policies/`,
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
				'mms:group': 'm-group:SuperAdmins',
				'mms:id': '"root',
			},

			'm-group:SuperAdmins': {
				a: 'mms:Group',
				'mms:id': '"super_admins',
			},
		},

		[factory.comment()]: 'cluster-specific classes',
		'm-graph:Cluster': {
			'm:': {
				a: 'mms:Cluster',
			},
		},

		[factory.comment()]: 'copy of static schema inherited from global MMS definitions',
		'm-graph:Schema': {
			[factory.comment()]: '====================================',
			[factory.comment()]: '==            Classes             ==',
			[factory.comment()]: '====================================',
			...classes({
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
			}),
		},

		'm-graph:AccessControl.Definitions': {
			[factory.comment()]: '====================================',
			[factory.comment()]: '==             Scopes             ==',
			[factory.comment()]: '====================================',
			...scopes({
				Cluster: {
					implies: 'Org',
				},
				Org: {
					implies: 'Project',
				},
				Project: {
					implies: 'Ref',
				},
				Ref: {},
			}),

			...classes({
				Collection: {
					super: 'Project',
				},
				Repo: {
					super: 'Project',
				},

				Branch: {
					super: 'Ref',
				},
				Lock: {
					super: 'Ref',
				},
			}),


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
							],
						},
						Delete: {
							implies: [
								'UpdateCluster',
								'DeleteOrg',
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
					crud: {
						Create: {},
						Read: {},
						Delete: {
							implies: ['ReadLock'],
						},
					},
				},

				// AccessControl: {
				// 	crud: {
				// 		Create: {
				// 			implies: [
				// 				'ReadAccessControl',
				// 				'CreatePolicy',
				// 				'CreateRole',
				// 				'CreateGroup',
				// 				'CreateUser',
				// 			],
				// 		},
				// 		Read: {},
				// 		Update: {

				// 		},
				// 	},
				// },
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
				AccessControl: H_ROLE_DEFAULT,
			}),
		},
	},
})