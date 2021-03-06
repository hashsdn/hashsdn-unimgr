/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.unimgr.mef.nrp.impl.connectivityservice;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.unimgr.mef.nrp.api.ActivationDriver;
import org.opendaylight.unimgr.mef.nrp.api.EndPoint;
import org.opendaylight.unimgr.mef.nrp.common.NrpDao;
import org.opendaylight.unimgr.mef.nrp.impl.ActivationTransaction;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.Uuid;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.Context1;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.DeleteConnectivityServiceInput;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.DeleteConnectivityServiceOutput;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.DeleteConnectivityServiceOutputBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connection.Route;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.Connection;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectionKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectivityService;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectivityServiceKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.delete.connectivity.service.output.Service;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.delete.connectivity.service.output.ServiceBuilder;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author bartosz.michalik@amartus.com
 */
public class DeleteConnectivityAction implements Callable<RpcResult<DeleteConnectivityServiceOutput>> {
    private static final Logger LOG = LoggerFactory.getLogger(DeleteConnectivityAction.class);

    private final DeleteConnectivityServiceInput input;
    private final TapiConnectivityServiceImpl service;
    private Uuid serviceId;
    private List<Uuid> connectionIds = new LinkedList<>();

    DeleteConnectivityAction(TapiConnectivityServiceImpl tapiConnectivityService, DeleteConnectivityServiceInput input) {
        Objects.requireNonNull(tapiConnectivityService);
        Objects.requireNonNull(input);
        this.service = tapiConnectivityService;
        this.input = input;
    }

    @Override
    public RpcResult<DeleteConnectivityServiceOutput> call() throws Exception {
        serviceId = new Uuid(input.getServiceIdOrName());
        NrpDao nrpDao = new NrpDao(service.getBroker().newReadOnlyTransaction());

        ConnectivityService cs =
                nrpDao.getConnectivityService(serviceId);
        if (cs == null) {
            return RpcResultBuilder
                    .<DeleteConnectivityServiceOutput>failed()
                    .withError(RpcError.ErrorType.APPLICATION, MessageFormat.format("Service {0} does not exist", input.getServiceIdOrName()))
                    .build();
        }
        Map<Uuid, LinkedList<EndPoint>> data = null;
        try {
            data = prepareData(cs, nrpDao);
        } catch (Exception e) {
            LOG.info("Service {} does not exists", input.getServiceIdOrName());
            return RpcResultBuilder
                    .<DeleteConnectivityServiceOutput>failed()
                    .withError(RpcError.ErrorType.APPLICATION, MessageFormat.format("error while preparing data for service {0} ", input.getServiceIdOrName()))
                    .build();
        }

        assert data != null;

        Service response = new ServiceBuilder(cs).build();

        try {
            ActivationTransaction tx = prepareTransaction(data);

            if (tx != null) {
                ActivationTransaction.Result txResult = tx.deactivate();
                if (txResult.isSuccessful()) {
                    LOG.info("ConnectivityService construct deactivated successfully, request = {} ", input);
                    removeConnectivity();

                    DeleteConnectivityServiceOutput result = new DeleteConnectivityServiceOutputBuilder()
                            .setService(new ServiceBuilder(response).build()).build();
                    return RpcResultBuilder.success(result).build();
                } else {
                    LOG.warn("CreateConnectivityService deactivation failed, reason = {}, request = {}", txResult.getMessage(), input);
                }
            }
            throw new IllegalStateException("no transaction created for delete connectivity request");
        } catch (Exception e) {
            LOG.warn("Exception in create connectivity service", e);
            return RpcResultBuilder
                    .<DeleteConnectivityServiceOutput>failed()
                    .build();
        }
    }

    private void removeConnectivity() throws TransactionCommitFailedException {
        WriteTransaction tx = service.getBroker().newWriteOnlyTransaction();
        InstanceIdentifier<Context1> conCtx = NrpDao.ctx().augmentation(Context1.class);
        LOG.debug("Removing connectivity service {}", serviceId.getValue());
        tx.delete(LogicalDatastoreType.OPERATIONAL, conCtx.child(ConnectivityService.class, new ConnectivityServiceKey(serviceId)));
        connectionIds.forEach(csId -> {
            LOG.debug("Removing connection {}", csId.getValue());
            tx.delete(LogicalDatastoreType.OPERATIONAL, conCtx.child(Connection.class, new ConnectionKey(csId)));
        });
        //TODO should be transactional with operations on deactivation
        tx.submit().checkedGet();
    }

    private ActivationTransaction prepareTransaction(Map<Uuid, LinkedList<EndPoint>> data) {
        assert data != null;
        ActivationTransaction tx = new ActivationTransaction();
        data.entrySet().stream().map(e -> {
            Optional<ActivationDriver> driver = service.getDriverRepo().getDriver(e.getKey());
            if (!driver.isPresent()) {
                throw new IllegalStateException(MessageFormat.format("driver {} cannot be created", e.getKey()));
            }
            driver.get().initialize(e.getValue(), serviceId.getValue(), null);
            LOG.debug("driver {} added to deactivation transaction", driver.get());
            return driver.get();
        }).forEach(tx::addDriver);
        return tx;
    }

    private Map<Uuid, LinkedList<EndPoint>> prepareData(ConnectivityService cs, NrpDao nrpDao) {

        assert cs.getConnection() != null && cs.getConnection().size() == 1;

        Uuid expConnectionId = cs.getConnection().get(0);
        connectionIds.add(expConnectionId);

        Connection connection = nrpDao.getConnection(expConnectionId);
        List<Route> route = connection.getRoute();
        assert route != null && route.size() == 1;

        List<Uuid> systemConnectionIds = route.get(0).getConnectionEndPoint();

        connectionIds.addAll(systemConnectionIds);

        return systemConnectionIds.stream().map(nrpDao::getConnection)
                .flatMap(c -> {
                    Uuid nodeId = c.getContainerNode();
                    return c.getConnectionEndPoint().stream().map(cep -> {
                        Optional<org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.service.EndPoint> optEndPoint = Optional.empty();
                        if (cs.getEndPoint() != null) {
                            optEndPoint = cs.getEndPoint().stream()
                                    .filter(endPoint1 -> endPoint1.getServiceInterfacePoint().getValue().contains(cep.getServerNodeEdgePoint().getValue()))
                                    .findFirst();
                        }
                        org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.service.EndPoint endPoint =
                                optEndPoint.isPresent() ? optEndPoint.get() : null;
                        EndPoint ep = new EndPoint(endPoint, null).setSystemNepUuid(cep.getServerNodeEdgePoint());
                        return new Pair(nodeId, ep);
                    });
                }).collect(Collectors.toMap(p -> p.getNodeId(), p -> new LinkedList<>(Arrays.asList(p.getEndPoint())), (ol, nl) -> {
                    ol.addAll(nl);
                    return ol;
                }));
    }

    private static  class Pair {
        private final Uuid nodeId;
        private final EndPoint endPoint;

        private Pair(Uuid nodeId, EndPoint endPoint) {
            this.nodeId = nodeId;
            this.endPoint = endPoint;
        }

        public Uuid getNodeId() {
            return nodeId;
        }

        public EndPoint getEndPoint() {
            return endPoint;
        }
    }
}
