package org.zstack.compute.vm;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.workflow.Flow;
import org.zstack.core.workflow.FlowTrigger;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.message.MessageReply;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.vm.VmInstanceSpec.VolumeSpec;
import org.zstack.header.volume.*;
import org.zstack.identity.AccountManager;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.function.Function;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VmAllocateVolumeFlow implements Flow {
    @Autowired
    protected DatabaseFacade dbf;
    @Autowired
    protected CloudBus bus;
    @Autowired
    protected AccountManager acntMgr;
    @Autowired
    protected ErrorFacade errf;

    private List<CreateVolumeMsg> prepareMsg(Map<String, Object> ctx) {
        VmInstanceSpec spec = (VmInstanceSpec) ctx.get(VmInstanceConstant.Params.VmInstanceSpec.toString());

        String accountUuid = acntMgr.getOwnerAccountUuidOfResource(spec.getVmInventory().getUuid());
        if (accountUuid == null) {
            throw new CloudRuntimeException(String.format("accountUuid for vm[uuid:%s] is null", spec.getVmInventory().getUuid()));
        }

        List<VolumeSpec> volumeSpecs = spec.getVolumeSpecs();
        List<CreateVolumeMsg> msgs = new ArrayList<CreateVolumeMsg>(volumeSpecs.size());
        for (VolumeSpec vspec : volumeSpecs) {
            CreateVolumeMsg msg = new CreateVolumeMsg();
            if (vspec.isRoot()) {
                msg.setName("ROOT-for-" + spec.getVmInventory().getName());
                msg.setDescription(String.format("Root volume for VM[uuid:%s]", spec.getVmInventory().getUuid()));
                msg.setRootImageUuid(spec.getImageSpec().getInventory().getUuid());
                if (ImageMediaType.ISO.toString().equals(spec.getImageSpec().getInventory().getMediaType())) {
                    msg.setFormat(VolumeFormat.getVolumeFormatByMasterHypervisorType(spec.getDestHost().getHypervisorType()).toString());
                } else {
                    VolumeFormat imageFormat = VolumeFormat.valueOf(spec.getImageSpec().getInventory().getFormat());
                    msg.setFormat(imageFormat.getOutputFormat(spec.getDestHost().getHypervisorType()));
                }
            } else {
                msg.setName("DATA");
                msg.setDescription(String.format("DataVolume-%s", spec.getVmInventory().getUuid()));
                msg.setFormat(VolumeFormat.getVolumeFormatByMasterHypervisorType(spec.getDestHost().getHypervisorType()).toString());
            }

            msg.setDiskOfferingUuid(vspec.getDiskOfferingUuid());
            msg.setSize(vspec.getSize());
            msg.setPrimaryStorageUuid(vspec.getPrimaryStorageInventory().getUuid());
            msg.setVmInstanceUuid(spec.getVmInventory().getUuid());
            msg.setVolumeType(vspec.isRoot() ? VolumeType.Root.toString() : VolumeType.Data.toString());
            msg.setAccountUuid(accountUuid);
            bus.makeLocalServiceId(msg, VolumeConstant.SERVICE_ID);
            msgs.add(msg);
        }

        return msgs;
    }

    @Override
    public void run(final FlowTrigger trigger, final Map data) {
        final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        List<CreateVolumeMsg> msgs = prepareMsg(data);
        bus.send(msgs, 1, new CloudBusListCallBack(trigger) {
            @Override
            public void run(List<MessageReply> replies) {
                ErrorCode err = null;
                for (MessageReply r : replies) {
                    if (r.isSuccess()) {
                        CreateVolumeReply cr = r.castReply();
                        VolumeInventory inv = cr.getInventory();
                        if (inv.getType().equals(VolumeType.Root.toString())) {
                            spec.setDestRootVolume(inv);
                        } else {
                            spec.getDestDataVolumes().add(inv);
                        }
                    } else {
                        err = r.getError();
                    }
                }

                if (err != null) {
                    trigger.fail(err);
                } else {
                    trigger.next();
                }
            }
        });
    }

    @Override
    public void rollback(final FlowTrigger chain, Map data) {
        VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        List<VolumeInventory> destVolumes = new ArrayList<VolumeInventory>(spec.getDestDataVolumes().size() + 1);
        if (spec.getDestRootVolume() != null) {
            destVolumes.add(spec.getDestRootVolume());
        }
        destVolumes.addAll(spec.getDestDataVolumes());

        final List<DeleteVolumeMsg> msgs = CollectionUtils.transformToList(destVolumes, new Function<DeleteVolumeMsg, VolumeInventory>() {
            @Override
            public DeleteVolumeMsg call(VolumeInventory arg) {
                DeleteVolumeMsg msg = new DeleteVolumeMsg();
                msg.setUuid(arg.getUuid());
                bus.makeTargetServiceIdByResourceUuid(msg, VolumeConstant.SERVICE_ID, arg.getUuid());
                return msg;
            }
        });

        if (msgs.isEmpty()) {
            chain.rollback();
            return;
        }

        bus.send(msgs, new CloudBusListCallBack(chain) {
            @Override
            public void run(List<MessageReply> replies) {
                chain.rollback();
            }
        });
    }
}
