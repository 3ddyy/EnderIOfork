package crazypants.enderio.machine.invpanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.enderio.core.api.common.util.ITankAccess;
import com.enderio.core.common.NBTAction;
import com.enderio.core.common.fluid.SmartTank;
import com.enderio.core.common.fluid.SmartTankFluidHandler;

import crazypants.enderio.base.filter.FilterRegistry;
import crazypants.enderio.base.filter.item.IItemFilter;
import crazypants.enderio.base.fluid.Fluids;
import crazypants.enderio.base.fluid.SmartTankFluidMachineHandler;
import crazypants.enderio.base.machine.baselegacy.AbstractInventoryMachineEntity;
import crazypants.enderio.base.machine.baselegacy.SlotDefinition;
import crazypants.enderio.base.machine.modes.IoMode;
import crazypants.enderio.base.network.PacketHandler;
import crazypants.enderio.conduits.conduit.TileConduitBundle;
import crazypants.enderio.conduits.conduit.item.IInventoryDatabaseServer;
import crazypants.enderio.conduits.conduit.item.IInventoryPanel;
import crazypants.enderio.conduits.conduit.item.ItemConduit;
import crazypants.enderio.conduits.conduit.item.ItemConduitNetwork;
import crazypants.enderio.machine.invpanel.HandleStoredCraftingRecipe.HandleStoredCraftingRecipeArrayList;
import crazypants.enderio.machine.invpanel.client.ClientDatabaseManager;
import crazypants.enderio.machine.invpanel.client.InventoryDatabaseClient;
import crazypants.enderio.machine.invpanel.config.InvpanelConfig;
import crazypants.enderio.machine.invpanel.init.InvpanelObject;
import crazypants.enderio.machines.machine.generator.zombie.IHasNutrientTank;
import crazypants.enderio.machines.machine.generator.zombie.PacketNutrientTank;
import info.loenwind.autosave.annotations.Storable;
import info.loenwind.autosave.annotations.Store;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

@Storable
public class TileInventoryPanel extends AbstractInventoryMachineEntity implements IInventoryPanel, ITankAccess.IExtendedTankAccess, IHasNutrientTank {

  public static final int SLOT_CRAFTING_START = 0;
  public static final int SLOT_CRAFTING_RESULT = 9;
  public static final int SLOT_VIEW_FILTER = 10;
  public static final int SLOT_RETURN_START = 11;

  public static final int MAX_STORED_CRAFTING_RECIPES = 6;

  @Store
  protected final SmartTank fuelTank;
  protected boolean tanksDirty;

  private IInventoryDatabaseServer dbServer;
  private InventoryDatabaseClient dbClient;

  @Store({ NBTAction.CLIENT, NBTAction.SAVE })
  private boolean active;
  @Store
  private boolean extractionDisabled;

  public InventoryPanelContainer eventHandler;
  private IItemFilter itemFilter;

  @Store
  private int guiSortMode;
  @Store
  private String guiFilterString = "";
  @Store
  private boolean guiSync;

  @Store(handler = HandleStoredCraftingRecipeArrayList.class)
  private final ArrayList<StoredCraftingRecipe> storedCraftingRecipes;

  public TileInventoryPanel() {
    super(new SlotDefinition(0, 8, 11, 20, 21, 20));
    this.fuelTank = new SmartTank(Fluids.NUTRIENT_DISTILLATION.getFluid(), InvpanelConfig.inventoryPanelFree.get() ? 0 : 2000);
    this.fuelTank.setTileEntity(this);
    this.fuelTank.setCanDrain(false);
    this.storedCraftingRecipes = new ArrayList<StoredCraftingRecipe>();
  }

  public IInventoryDatabaseServer getDatabaseServer() {
    return dbServer;
  }

  public InventoryDatabaseClient getDatabaseClient(int generation) {
    if (dbClient != null && dbClient.getGeneration() != generation) {
      ClientDatabaseManager.INSTANCE.destroyDatabase(dbClient.getGeneration());
      dbClient = null;
    }
    if (dbClient == null) {
      dbClient = ClientDatabaseManager.INSTANCE.getOrCreateDatabase(generation);
    }
    return dbClient;
  }

  public @Nullable InventoryDatabaseClient getDatabaseClient() {
    return dbClient;
  }

  @Override
  public boolean isValidInput(@Nonnull ItemStack itemstack) {
    return false;
  }

  @Override
  public boolean isValidOutput(@Nonnull ItemStack itemstack) {
    return !extractionDisabled && super.isValidOutput(itemstack);
  }

  @Override
  public boolean isMachineItemValidForSlot(int slot, ItemStack stack) {
    if (slot == SLOT_VIEW_FILTER && stack != null) {
      return FilterRegistry.isItemFilter(stack) && FilterRegistry.isFilterSet(stack);
    }
    return true;
  }

  private static IInventory emptyInventory = new InventoryBasic("[Null]", true, 0);

  @Override
  public void setInventorySlotContents(int slot, @Nonnull ItemStack contents) {
    super.setInventorySlotContents(slot, contents);
    if (slot < SLOT_CRAFTING_RESULT && eventHandler != null) {
      eventHandler.onCraftMatrixChanged(emptyInventory);
    }
    if (slot == SLOT_VIEW_FILTER) {
      updateItemFilter();
    }
  }

  private void updateItemFilter() {
    itemFilter = FilterRegistry.<IItemFilter> getFilterForUpgrade(inventory[SLOT_VIEW_FILTER]);
  }

  public IItemFilter getItemFilter() {
    return itemFilter;
  }

  @Override
  public boolean isActive() {
    return InvpanelConfig.inventoryPanelFree.get() || active;
  }

  @Override
  public void doUpdate() {
    if (world.isRemote) {
      updateEntityClient();
      return;
    }

    if (shouldDoWorkThisTick(20)) {
      scanNetwork();
    }

    if (updateClients) {
      forceUpdatePlayers();
      markDirty();
      updateClients = false;
    }

    if (tanksDirty) {
      tanksDirty = false;
      PacketHandler.sendToAllAround(new PacketNutrientTank(this), this);
    }
  }

  private void scanNetwork() {
    EnumFacing facingDir = getFacing();
    EnumFacing backside = facingDir.getOpposite();

    ItemConduitNetwork icn = null;

    BlockPos p = pos.offset(backside);
    TileEntity te = world.getTileEntity(p);
    if (te instanceof TileConduitBundle) {
      TileConduitBundle teCB = (TileConduitBundle) te;
      ItemConduit conduit = teCB.getConduit(ItemConduit.class);
      if (conduit != null) {
        icn = (ItemConduitNetwork) conduit.getNetwork();
      }
    }

    if (icn != null) {
      dbServer = icn.getDatabase();
      dbServer.sendChangeLogs();
      refuelPower(dbServer);

      if (active != dbServer.isOperational()) {
        active = dbServer.isOperational();
        updateClients = true;
      }
    } else {
      if (active) {
        updateClients = true;
      }
      dbServer = null;
      active = false;
    }
  }

  @Override
  public float getAvailablePower() {
    return getPower() * InvpanelConfig.inventoryPanelPowerPerMB.get();
  }

  @Override
  public void refuelPower(IInventoryDatabaseServer db) {
    float missingPower = InvpanelConfig.inventoryPanelPowerPerMB.get() * 0.5f - db.getPower();
    if (missingPower > 0) {
      int amount = (int) Math.ceil(missingPower / InvpanelConfig.inventoryPanelPowerPerMB.get());
      amount = Math.min(amount, getPower());
      if (amount > 0) {
        useNutrient(amount);
        dbServer.addPower(amount * InvpanelConfig.inventoryPanelPowerPerMB.get());
      }
    }
  }

  public void useNutrient(int amount) {
    fuelTank.removeFluidAmount(amount);
  }

  private int getPower() {
    return InvpanelConfig.inventoryPanelFree.get() ? 100 : fuelTank.getFluidAmount();
  }

  @Override
  protected boolean processTasks(boolean redstoneCheck) {
    return false;
  }

  public int getGuiSortMode() {
    return guiSortMode;
  }

  public String getGuiFilterString() {
    return guiFilterString;
  }

  public boolean getGuiSync() {
    return guiSync;
  }

  public void setGuiParameter(int sortMode, String filterString, boolean sync) {
    this.guiSortMode = sortMode;
    this.guiFilterString = filterString;
    this.guiSync = sync;
    if (world != null && !world.isRemote) {
      PacketHandler.INSTANCE.sendToDimension(new PacketGuiSettingsUpdated(this), world.provider.getDimension());
      markDirty();
    }
  }

  public int getStoredCraftingRecipes() {
    return storedCraftingRecipes.size();
  }

  public StoredCraftingRecipe getStoredCraftingRecipe(int index) {
    if (index < 0 || index >= storedCraftingRecipes.size()) {
      return null;
    }
    return storedCraftingRecipes.get(index);
  }

  public void addStoredCraftingRecipe(StoredCraftingRecipe recipe) {
    storedCraftingRecipes.add(recipe);
    if (world == null || world.isRemote) {
      PacketHandler.INSTANCE.sendToServer(new PacketStoredCraftingRecipe(PacketStoredCraftingRecipe.ACTION_ADD, 0, recipe));
    } else {
      markDirty();
      updateBlock();
    }
  }

  public void removeStoredCraftingRecipe(int index) {
    if (index >= 0 && index < storedCraftingRecipes.size()) {
      storedCraftingRecipes.remove(index);
      if (world == null || world.isRemote) {
        PacketHandler.INSTANCE.sendToServer(new PacketStoredCraftingRecipe(PacketStoredCraftingRecipe.ACTION_DELETE, index, null));
      } else {
        markDirty();
        updateBlock();
      }
    }
  }

  public boolean isExtractionDisabled() {
    return extractionDisabled;
  }

  public void setExtractionDisabled(boolean extractionDisabled) {
    this.extractionDisabled = extractionDisabled;
    if (world != null) {
      if (!world.isRemote) {
        PacketHandler.INSTANCE.sendToDimension(new PacketUpdateExtractionDisabled(this, extractionDisabled), world.provider.getDimension());
      }
    }
  }

  /**
   * This is called by PacketUpdateExtractionDisabled on the client side
   * 
   * @param extractionDisabledIn
   *          if extraction is disabled
   */
  void updateExtractionDisabled(boolean extractionDisabledIn) {
    this.extractionDisabled = extractionDisabledIn;
  }

  @Override
  protected void onAfterNbtRead() {
    faceModes = null;
    if (eventHandler != null) {
      eventHandler.checkCraftingRecipes();
    }
    updateItemFilter();
  }

  @Override
  public @Nonnull String getMachineName() {
    return InvpanelObject.blockInventoryPanel.getUnlocalisedName();
  }

  @Override
  public @Nonnull IoMode getIoMode(@Nullable EnumFacing face) {
    return face == getIODirection() ? IoMode.NONE : IoMode.DISABLED;
  }

  @Override
  public void setIoMode(@Nullable EnumFacing faceHit, @Nullable IoMode mode) {
  }

  @Override
  public @Nonnull IoMode toggleIoModeForFace(@Nullable EnumFacing faceHit) {
    return getIoMode(faceHit);
  }

  private EnumFacing getIODirection() {
    return getFacing().getOpposite();
  }

  @Override
  public FluidTank getInputTank(FluidStack forFluidType) {
    if (forFluidType != null && fuelTank.canFill(forFluidType.getFluid())) {
      return fuelTank;
    }
    return null;
  }

  @Override
  public @Nonnull FluidTank[] getOutputTanks() {
    return new FluidTank[0];
  }

  @Override
  public void setTanksDirty() {
    tanksDirty = true;
  }

  @Override
  public SmartTank getNutrientTank() {
    return fuelTank;
  }

  @SuppressWarnings("null")
  @Override
  @Nonnull
  public List<ITankData> getTankDisplayData() {
    return Collections.<ITankData> singletonList(new ITankData() {

      @Override
      @Nonnull
      public EnumTankType getTankType() {
        return EnumTankType.INPUT;
      }

      @Override
      @Nullable
      public FluidStack getContent() {
        return fuelTank.getFluid();
      }

      @Override
      public int getCapacity() {
        return fuelTank.getCapacity();
      }
    });
  }

  private SmartTankFluidHandler smartTankFluidHandler;

  protected SmartTankFluidHandler getSmartTankFluidHandler() {
    if (smartTankFluidHandler == null) {
      smartTankFluidHandler = new SmartTankFluidMachineHandler(this, fuelTank);
    }
    return smartTankFluidHandler;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getCapability(Capability<T> capability, EnumFacing facingIn) {
    if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
      return (T) getSmartTankFluidHandler().get(facingIn);
    }
    return super.getCapability(capability, facingIn);
  }

}
