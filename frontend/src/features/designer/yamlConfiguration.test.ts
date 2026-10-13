import { describe, expect, it } from 'vitest';

import type { YamlFieldRef } from './yamlConfiguration';
import { draftValuesFromYaml, yamlFromFields } from './yamlConfiguration';

function fields(): readonly YamlFieldRef[] {
  return [
    {
      instanceId: 'source',
      field: {
        key: 'host',
        label: 'Host',
        renderedPath: 'solace.host',
        controlKind: 'text',
        value: { kind: 'value', value: 'broker.example', source: 'draft' },
      },
    },
    {
      instanceId: 'source',
      field: {
        key: 'port',
        label: 'Port',
        renderedPath: 'solace.port',
        controlKind: 'number',
        value: { kind: 'value', value: 55555, source: 'draft' },
      },
    },
    {
      instanceId: 'source',
      field: {
        key: 'password',
        label: 'Password',
        renderedPath: 'solace.password',
        controlKind: 'secret',
        value: { kind: 'secretUnset' },
      },
    },
    {
      instanceId: 'source',
      field: {
        key: 'vpn',
        label: 'VPN',
        renderedPath: 'solace.vpn',
        controlKind: 'text',
        value: { kind: 'unset' },
      },
    },
  ];
}

describe('YAML configuration projection', () => {
  it('serializes package paths and omits sensitive values', () => {
    const result = yamlFromFields(fields());

    expect(result.text).toContain('solace:');
    expect(result.text).toContain('host: broker.example');
    expect(result.text).toContain('port: 55555');
    expect(result.text).not.toContain('password:');
    expect(result.omittedSecrets).toBe(1);
  });

  it('includes unset fields as null so all package fields are visible', () => {
    const result = yamlFromFields(fields());

    expect(result.text).toContain('vpn: null');
  });

  it('leaves an untouched null placeholder for an unset field as a no-op', () => {
    const result = draftValuesFromYaml(
      ['solace:', '  host: only-host', '  vpn: null'].join('\n'),
      fields(),
    );

    expect(result.error).toBeUndefined();
    expect(result.values).toEqual({ source: { host: 'only-host' } });
  });

  it('maps edited YAML back to the shared instance draft', () => {
    const result = draftValuesFromYaml(
      ['solace:', '  host: changed.example', '  port: 55556'].join('\n'),
      fields(),
    );

    expect(result.error).toBeUndefined();
    expect(result.values).toEqual({
      source: { host: 'changed.example', port: 55556 },
    });
  });

  it('reports syntax, root-shape, and field-type errors', () => {
    expect(draftValuesFromYaml('solace: [', fields()).error).toMatch(/YAML syntax error/);
    expect(draftValuesFromYaml('- not a mapping', fields()).error).toMatch(/mapping\/object/);
    expect(draftValuesFromYaml('solace:\n  port: not-a-number', fields()).error).toMatch(
      /solace\.port/,
    );
    expect(draftValuesFromYaml('unknown: value', fields()).error).toMatch(
      /not defined by this package/,
    );
  });

  it('keeps omitted fields unchanged so secrets and partial edits survive', () => {
    const result = draftValuesFromYaml('solace:\n  host: only-host', fields());

    expect(result.error).toBeUndefined();
    expect(result.values).toEqual({ source: { host: 'only-host' } });
  });

  it('does not convert an unchanged connection-derived value into an override', () => {
    const connectionField: YamlFieldRef = {
      instanceId: 'source',
      field: {
        key: 'host',
        label: 'Host',
        renderedPath: 'solace.host',
        controlKind: 'text',
        value: { kind: 'value', value: 'connection-host', source: 'connection' },
      },
    };

    expect(
      draftValuesFromYaml('solace:\n  host: connection-host', [connectionField]).values,
    ).toEqual({});
    expect(draftValuesFromYaml('solace:\n  host: override-host', [connectionField]).values).toEqual(
      {
        source: { host: 'override-host' },
      },
    );
  });
});
