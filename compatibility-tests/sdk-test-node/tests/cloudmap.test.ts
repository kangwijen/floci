/**
 * Cloud Map (servicediscovery) compatibility tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ServiceDiscoveryClient,
  CreateHttpNamespaceCommand,
  ListNamespacesCommand,
  GetNamespaceCommand,
  CreateServiceCommand,
  RegisterInstanceCommand,
  ListInstancesCommand,
  DeregisterInstanceCommand,
  DeleteServiceCommand,
  DeleteNamespaceCommand,
  GetOperationCommand,
  ServiceTypeOption,
} from '@aws-sdk/client-servicediscovery';
import { makeClient, uniqueName, sleep } from './setup';

const INSTANCE_ID = 'i-cm-node-1';

function isServiceDisabled(err: unknown): boolean {
  const e = err as { name?: string; message?: string };
  return (
    e.name === 'ServiceUnavailableException' ||
    (e.message?.toLowerCase().includes('not enabled') ?? false)
  );
}

async function awaitSuccess(sd: ServiceDiscoveryClient, operationId: string): Promise<void> {
  for (let i = 0; i < 20; i++) {
    const op = await sd.send(new GetOperationCommand({ OperationId: operationId }));
    const status = op.Operation?.Status;
    if (status === 'SUCCESS') return;
    if (status === 'FAIL') {
      throw new Error(`Operation ${operationId} failed: ${op.Operation?.ErrorMessage}`);
    }
    await sleep(100);
  }
  throw new Error(`Operation ${operationId} did not reach SUCCESS`);
}

describe('Cloud Map', () => {
  let sd: ServiceDiscoveryClient;
  let namespaceName: string;
  let serviceName: string;
  let namespaceId = '';
  let serviceId = '';

  beforeAll(() => {
    sd = makeClient(ServiceDiscoveryClient);
    namespaceName = uniqueName('cm-ns');
    serviceName = uniqueName('cm-svc');
  });

  afterAll(async () => {
    try {
      if (serviceId) {
        await sd
          .send(new DeregisterInstanceCommand({ ServiceId: serviceId, InstanceId: INSTANCE_ID }))
          .catch(() => {});
        await sd.send(new DeleteServiceCommand({ Id: serviceId })).catch(() => {});
      }
      if (namespaceId) {
        await sd.send(new DeleteNamespaceCommand({ Id: namespaceId })).catch(() => {});
      }
    } catch {
      // ignore cleanup errors
    }
  });

  it('creates namespace, service, and registers an instance', async (ctx) => {
    let createNs;
    try {
      createNs = await sd.send(
        new CreateHttpNamespaceCommand({
          Name: namespaceName,
          Description: 'floci sdk compat',
        }),
      );
    } catch (err) {
      if (isServiceDisabled(err)) {
        ctx.skip();
      }
      throw err;
    }

    await awaitSuccess(sd, createNs.OperationId!);

    const listed = await sd.send(new ListNamespacesCommand({}));
    const found = listed.Namespaces?.find((n) => n.Name === namespaceName);
    expect(found).toBeDefined();
    namespaceId = found!.Id!;
    expect(namespaceId).toMatch(/^ns-/);
    expect(found!.Type).toBe('HTTP');
    expect(found!.Arn).toContain('servicediscovery');

    const getNs = await sd.send(new GetNamespaceCommand({ Id: namespaceId }));
    expect(getNs.Namespace?.Name).toBe(namespaceName);

    const createSvc = await sd.send(
      new CreateServiceCommand({
        Name: serviceName,
        NamespaceId: namespaceId,
        Type: ServiceTypeOption.HTTP,
      }),
    );
    serviceId = createSvc.Service?.Id ?? '';
    expect(serviceId).toMatch(/^srv-/);
    expect(createSvc.Service?.Name).toBe(serviceName);

    const reg = await sd.send(
      new RegisterInstanceCommand({
        ServiceId: serviceId,
        InstanceId: INSTANCE_ID,
        Attributes: {
          AWS_INSTANCE_IPV4: '10.0.0.7',
          AWS_INSTANCE_PORT: '9090',
        },
      }),
    );
    await awaitSuccess(sd, reg.OperationId!);

    const instances = await sd.send(new ListInstancesCommand({ ServiceId: serviceId }));
    expect(instances.Instances).toHaveLength(1);
    expect(instances.Instances![0].Id).toBe(INSTANCE_ID);
    expect(instances.Instances![0].Attributes?.AWS_INSTANCE_IPV4).toBe('10.0.0.7');
  });
});
