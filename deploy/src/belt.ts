export type Hash<K=string> = Record<string, K>;

export const ode = Object.entries;

export function oderom<
	OutType extends any,
	ValueType extends any,
>(h_thing: Hash<ValueType>, f_concat: (si_key: string, w_value: ValueType) => Hash<OutType>): Hash<OutType> {
	return ode(h_thing).reduce((h_out, [si_key, w_value]) => ({
		...h_out,
		...f_concat(si_key, w_value),
	}), {} as Hash<OutType>);
}
