/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jclouds.virtualbox.functions;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.virtualbox.util.MachineUtils.lockMachineAndApply;
import static org.virtualbox_4_1.LockType.Write;

import java.io.File;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.Resource;
import javax.inject.Named;

import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.logging.Logger;
import org.jclouds.virtualbox.domain.DeviceDetails;
import org.jclouds.virtualbox.domain.HardDisk;
import org.jclouds.virtualbox.domain.IsoImage;
import org.jclouds.virtualbox.domain.NatAdapter;
import org.jclouds.virtualbox.domain.StorageController;
import org.jclouds.virtualbox.domain.VmSpec;
import org.jclouds.virtualbox.util.PropertyUtils;
import org.virtualbox_4_1.AccessMode;
import org.virtualbox_4_1.DeviceType;
import org.virtualbox_4_1.IMachine;
import org.virtualbox_4_1.IMedium;
import org.virtualbox_4_1.IVirtualBox;
import org.virtualbox_4_1.VBoxException;
import org.virtualbox_4_1.VirtualBoxManager;

import com.google.common.base.Function;

/**
 * @author Mattias Holmqvist
 */
public class CreateAndRegisterMachineFromIsoIfNotAlreadyExists implements Function<VmSpec, IMachine> {
   
   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;
   private VirtualBoxManager manager;

   public CreateAndRegisterMachineFromIsoIfNotAlreadyExists(VirtualBoxManager manager) {
      this.manager = manager;
   }

   @Override
   public IMachine apply(@Nullable VmSpec launchSpecification) {
      final IVirtualBox vBox = manager.getVBox();
      String vmName = launchSpecification.getVmName();
      try {
         vBox.findMachine(vmName);
         throw new IllegalStateException("Machine " + vmName + " is already registered.");
      } catch (VBoxException e) {
         if (machineNotFoundException(e))
            return createMachine(vBox, launchSpecification);
         else
            throw e;
      }
   }

   private boolean machineNotFoundException(VBoxException e) {
      return e.getMessage().contains("VirtualBox error: Could not find a registered machine named ");
   }

   private IMachine createMachine(IVirtualBox vBox, VmSpec vmSpec) {
      String workingDir = PropertyUtils.getWorkingDirFromProperty();
      String settingsFile = vBox.composeMachineFilename(vmSpec.getVmName() , workingDir);

      IMachine newMachine = vBox.createMachine(settingsFile, vmSpec.getVmName(),
            vmSpec.getOsTypeId(), vmSpec.getVmId(),  vmSpec.isForceOverwrite());
      manager.getVBox().registerMachine(newMachine);
      
      String vmName = vmSpec.getVmName();
      
      // Change RAM
      ensureMachineHasMemory(vmName, vmSpec.getMemory());

      Set<StorageController> controllers = vmSpec.getControllers();
      if (controllers.isEmpty()) {
         throw new IllegalStateException(missingIDEControllersMessage(vmSpec));
      }
      StorageController controller = controllers.iterator().next();
      ensureMachineHasStorageControllerNamed(vmName, controller);
      setupHardDisksForController(vmName, controller);
      setupDvdsForController(vmSpec, vmName, controller);

      // NAT
      Map<Long, NatAdapter> natNetworkAdapters = vmSpec.getNatNetworkAdapters();
      for (Map.Entry<Long, NatAdapter> natAdapterAndSlot : natNetworkAdapters.entrySet()) {
         long slotId = natAdapterAndSlot.getKey();
         NatAdapter natAdapter = natAdapterAndSlot.getValue();
         ensureNATNetworkingIsAppliedToMachine(vmName, slotId, natAdapter);
      }
      return newMachine;
   }
   
   private void setupDvdsForController(VmSpec vmSpecification, String vmName, StorageController controller) {
      Set<IsoImage> dvds = controller.getIsoImages();
      for (IsoImage dvd : dvds) {
         String dvdSource = dvd.getSourcePath();
         final IMedium dvdMedium = manager.getVBox().openMedium(dvdSource, DeviceType.DVD,
                 AccessMode.ReadOnly, vmSpecification.isForceOverwrite());
         ensureMachineDevicesAttached(vmName, dvdMedium, dvd.getDeviceDetails(), controller.getName());
      }
   }
   
   private void ensureMachineDevicesAttached(String vmName, IMedium medium, DeviceDetails deviceDetails, String controllerName) {
      lockMachineAndApply(manager, Write, vmName, new AttachMediumToMachineIfNotAlreadyAttached(deviceDetails, medium, controllerName));
   }
   
   private String missingIDEControllersMessage(VmSpec vmSpecification) {
      return String.format("First controller is not an IDE controller. Please verify that the VM spec is a correct master node: %s", vmSpecification);
   }
   
   private void setupHardDisksForController(String vmName, StorageController controller) {
      Set<HardDisk> hardDisks = controller.getHardDisks();
      for (HardDisk hardDisk : hardDisks) {
         String sourcePath = hardDisk.getDiskPath();
         if (new File(sourcePath).exists()) {
            boolean deleted = new File(sourcePath).delete();
            if (!deleted) {
               logger.error(String.format("File %s could not be deleted.", sourcePath));
            }
         }
         IMedium medium = new CreateMediumIfNotAlreadyExists(manager, true).apply(hardDisk);
         ensureMachineDevicesAttached(vmName, medium, hardDisk.getDeviceDetails(), controller.getName());
      }
   }

   private void ensureMachineHasMemory(String vmName, final long memorySize) {
      lockMachineAndApply(manager, Write, vmName, new ApplyMemoryToMachine(memorySize));
   }

   private void ensureNATNetworkingIsAppliedToMachine(String vmName, long slotId, NatAdapter natAdapter) {
      lockMachineAndApply(manager, Write, vmName, new AttachNATAdapterToMachine(slotId, natAdapter));
   }

   public void ensureMachineHasStorageControllerNamed(String vmName, StorageController storageController) {
      lockMachineAndApply(manager, Write, checkNotNull(vmName, "vmName"),
              new AddIDEControllerIfNotExists(checkNotNull(storageController, "storageController")));
   }
}