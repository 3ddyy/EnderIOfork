package crazypants.enderio.conduit.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import buildcraft.api.power.IPowerReceptor;
import crazypants.enderio.conduit.power.PowerConduitNetwork.ReceptorEntry;
import crazypants.enderio.machine.power.TileCapacitorBank;
import crazypants.enderio.power.EnderPowerProvider;
import crazypants.enderio.power.IInternalPowerReceptor;
import crazypants.enderio.power.MutablePowerProvider;
import crazypants.enderio.power.PowerHandlerUtil;
import crazypants.util.BlockCoord;

public class NetworkPowerManager {

  private PowerConduitNetwork network;

  int maxEnergyStored;
  float energyStored;
  private float reserved;

  private int updateRenderTicks = 10;
  private int inactiveTicks = 100;

  private final List<ReceptorEntry> receptors = new ArrayList<PowerConduitNetwork.ReceptorEntry>();
  private ListIterator<ReceptorEntry> receptorIterator = receptors.listIterator();

  private final List<ReceptorEntry> storageReceptors = new ArrayList<ReceptorEntry>();

  private boolean lastActiveValue = false;
  private int ticksWithNoPower = 0;

  private final Map<BlockCoord, StarveBuffer> starveBuffers = new HashMap<BlockCoord, NetworkPowerManager.StarveBuffer>();

  public NetworkPowerManager(PowerConduitNetwork netowrk, World world) {
    this.network = netowrk;
    maxEnergyStored = 64;
  }

  public void applyRecievedPower() {

    // Update our energy stored based on what's in our conduits
    updateNetorkStorage();
    checkReserves();
    updateActiveState();
    CapBankSupply capSupply = new CapBankSupply();

    int appliedCount = 0;
    int numReceptors = receptors.size();
    float available = energyStored + capSupply.canExtract - reserved;
    float wasAvailable = available;

    if (available <= 0 || (receptors.isEmpty() && storageReceptors.isEmpty())) {
      return;
    }

    while (available > 0 && appliedCount < numReceptors) {

      if (!receptors.isEmpty() && !receptorIterator.hasNext()) {
        receptorIterator = receptors.listIterator();
      }

      ReceptorEntry r = receptorIterator.next();
      if (r.emmiter.getPowerHandler().isPowerSource(r.direction)) {

        // do a dummy receive energy so counter will never tick down
        float es = r.emmiter.getPowerHandler().getEnergyStored();
        MutablePowerProvider pr = r.emmiter.getPowerHandler();
        pr.receiveEnergy(0, null);
        pr.setEnergy(energyStored);

      } else {

        IPowerReceptor pp = r.powerReceptor;
        if (pp != null && pp.getPowerProvider() != null) {

          float used = 0;
          float reservedForEntry = removeReservedEnergy(r);
          float canOffer = available + reservedForEntry;
          canOffer = Math.min(r.emmiter.getMaxEnergyExtracted(r.direction), canOffer);

          float requested = pp.powerRequest(r.direction);
          requested = Math.min(requested, pp.getPowerProvider().getMaxEnergyStored() - pp.getPowerProvider().getEnergyStored());
          requested = Math.min(requested, pp.getPowerProvider().getMaxEnergyReceived());

          // If it is possible to supply the minimum amount of energy
          if (pp.getPowerProvider().getMinEnergyReceived() <= r.emmiter.getMaxEnergyExtracted(r.direction)) {
            // Buffer energy if we can't meet it now
            if (pp.getPowerProvider().getMinEnergyReceived() > canOffer && requested > 0) {
              if (pp.getPowerProvider().getMinEnergyReceived() < r.emmiter.getMaxEnergyExtracted(r.direction)) {
                reserveEnergy(r, canOffer);
                used += canOffer;
              }
            } else if (r.powerReceptor instanceof IInternalPowerReceptor) {
              used = PowerHandlerUtil.transmitInternal((IInternalPowerReceptor) r.powerReceptor, canOffer, r.direction.getOpposite());
            } else {
              used = Math.min(requested, canOffer);
              pp.getPowerProvider().receiveEnergy(used, r.direction.getOpposite());
            }

          }
          available -= used;

          if (available <= 0) {
            break;
          }
        }

      }
      appliedCount++;
    }

    float used = wasAvailable - available;
    // use all the capacator storage first
    energyStored -= used;

    float capBankChange = 0;
    if (energyStored < 0) {
      // not enough so get the rest from the capacitor bank
      capBankChange = energyStored;
      energyStored = 0;
    } else if (energyStored > 0) {
      // push as much as we can back to the cap banks
      capBankChange = Math.min(energyStored, capSupply.canFill);
      energyStored -= capBankChange;
    }

    if (capBankChange < 0) {
      capSupply.remove(Math.abs(capBankChange));
    } else if (capBankChange > 0) {
      capSupply.add(capBankChange);
    }

    capSupply.balance();

    distributeStorageToConduits();
  }

  private void updateActiveState() {
    boolean active;
    if (energyStored > 0) {
      ticksWithNoPower = 0;
      active = true;
    } else {
      ticksWithNoPower++;
      active = false;
    }

    boolean doRender = active != lastActiveValue && (active || (!active && ticksWithNoPower > updateRenderTicks));
    if (doRender) {
      lastActiveValue = active;
      for (IPowerConduit con : network.getConduits()) {
        con.setActive(active);
      }
    }
  }

  private float removeReservedEnergy(ReceptorEntry r) {
    StarveBuffer starveBuf = starveBuffers.remove(r.coord);
    if (starveBuf == null) {
      return 0;
    }
    float result = starveBuf.stored;
    reserved -= result;
    return result;
  }

  private void reserveEnergy(ReceptorEntry r, float amount) {
    starveBuffers.put(r.coord, new StarveBuffer(amount));
    reserved += amount;
  }

  private void checkReserves() {
    if (reserved > maxEnergyStored * 0.9) {
      starveBuffers.clear();
      reserved = 0;
    }
  }

  private void distributeStorageToConduits() {
    if (maxEnergyStored <= 0 || energyStored <= 0) {
      for (IPowerConduit con : network.getConduits()) {
        con.getPowerHandler().setEnergy(0);
      }
      return;
    }
    if (energyStored > maxEnergyStored) {
      energyStored = maxEnergyStored;
    }

    float filledRatio = energyStored / maxEnergyStored;
    float energyLeft = energyStored;
    float given = 0;
    for (IPowerConduit con : network.getConduits()) {
      // NB: use ceil to ensure we dont through away any energy due to rounding
      // errors
      float give = (float) Math.ceil(con.getCapacitor().getMaxEnergyStored() * filledRatio);
      give = Math.min(give, con.getCapacitor().getMaxEnergyStored());
      give = Math.min(give, energyLeft);
      con.getPowerHandler().setEnergy(give);
      given += give;
      energyLeft -= give;
      if (energyLeft <= 0) {
        return;
      }
    }
  }

  boolean isActive() {
    return energyStored > 0;
  }

  private void updateNetorkStorage() {
    maxEnergyStored = 0;
    energyStored = 0;
    for (IPowerConduit con : network.getConduits()) {
      maxEnergyStored += con.getCapacitor().getMaxEnergyStored();
      energyStored += con.getPowerHandler().getEnergyStored();
    }

    if (energyStored > maxEnergyStored) {
      energyStored = maxEnergyStored;
    }

  }

  public void receptorsChanged() {
    receptors.clear();
    storageReceptors.clear();
    for (ReceptorEntry rec : network.getPowerReceptors()) {
      if (rec.powerReceptor instanceof TileCapacitorBank) {
        storageReceptors.add(rec);
      } else {
        receptors.add(rec);
      }
    }
    receptorIterator = receptors.listIterator();
  }

  void onNetworkDestroyed() {
  }

  private static class StarveBuffer {

    float stored;

    public StarveBuffer(float stored) {
      this.stored = stored;
    }

    void addToStore(float val) {
      stored += val;
    }

  }

  private float minAbs(float amount, float limit) {
    if (amount < 0) {
      return Math.max(amount, -limit);
    } else {
      return Math.min(amount, limit);
    }
  }

  private class CapBankSupply {

    float canExtract;
    float canFill;
    float filledRatio;
    float stored = 0;
    float maxCap = 0;

    List<CapBankSupplyEntry> enteries;

    CapBankSupply() {
      init();
    }

    void init() {
      canExtract = 0;
      canFill = 0;
      stored = 0;
      maxCap = 0;
      enteries = new ArrayList<NetworkPowerManager.CapBankSupplyEntry>();
      for (ReceptorEntry rec : storageReceptors) {
        TileCapacitorBank cb = (TileCapacitorBank) rec.powerReceptor;

        stored += cb.getEnergyStored();
        maxCap += cb.getMaxEnergyStored();

        float canGet = 0;
        if (cb.isOutputEnabled()) {
          canGet = Math.min(cb.getEnergyStored(), cb.getMaxIO());
          canGet = Math.min(canGet, rec.emmiter.getMaxEnergyRecieved(rec.direction));
          canExtract += canGet;
        }
        float canFill = 0;
        if (cb.isInputEnabled()) {
          canFill = Math.min(cb.getMaxEnergyStored() - cb.getEnergyStored(), cb.getMaxIO());
          canFill = Math.min(canFill, rec.emmiter.getMaxEnergyExtracted(rec.direction));
          this.canFill += canFill;
        }
        enteries.add(new CapBankSupplyEntry(cb, canGet, canFill));

      }

      filledRatio = 0;
      if (maxCap > 0) {
        filledRatio = stored / maxCap;
      }
    }

    void balance() {
      if (enteries.size() < 2) {
        return;
      }
      init();
      int canRemove = 0;
      int canAdd = 0;
      for (CapBankSupplyEntry entry : enteries) {
        entry.calcToBalance(filledRatio);
        if (entry.toBalance < 0) {
          canRemove += -entry.toBalance;
        } else {
          canAdd += entry.toBalance;
        }
      }

      float toalTransferAmount = Math.min(canAdd, canRemove);

      for (int i = 0; i < enteries.size() && toalTransferAmount > 0; i++) {
        CapBankSupplyEntry from = enteries.get(i);
        float amount = from.toBalance;
        amount = minAbs(amount, toalTransferAmount);
        from.capBank.addEnergy(amount);
        toalTransferAmount -= Math.abs(amount);
        float toTranfser = Math.abs(amount);

        for (int j = i + 1; j < enteries.size() && toTranfser > 0; j++) {
          CapBankSupplyEntry to = enteries.get(j);
          if (Math.signum(amount) != Math.signum(to.toBalance)) {
            float toAmount = Math.min(toTranfser, Math.abs(to.toBalance));
            to.capBank.addEnergy(toAmount * Math.signum(to.toBalance));
            toTranfser -= toAmount;
          }
        }

      }

    }

    void remove(float amount) {
      if (canExtract <= 0 || amount <= 0) {
        return;
      }
      float ratio = amount / canExtract;

      for (CapBankSupplyEntry entry : enteries) {
        double use = Math.ceil(ratio * entry.canExtract);
        use = Math.min(use, amount);
        use = Math.min(use, entry.canExtract);
        entry.capBank.addEnergy(-(float) use);
        amount -= use;
        if (amount == 0) {
          return;
        }
      }
    }

    void add(float amount) {
      if (canFill <= 0 || amount <= 0) {
        return;
      }
      float ratio = amount / canFill;

      for (CapBankSupplyEntry entry : enteries) {
        double add = (int) Math.ceil(ratio * entry.canFill);
        add = Math.min(add, entry.canFill);
        add = Math.min(add, amount);
        entry.capBank.addEnergy((float) add);
        amount -= add;
        if (amount == 0) {
          return;
        }
      }
    }

  }

  private static class CapBankSupplyEntry {

    final TileCapacitorBank capBank;
    final float canExtract;
    final float canFill;
    float toBalance;

    private CapBankSupplyEntry(TileCapacitorBank capBank, float available, float canFill) {
      this.capBank = capBank;
      this.canExtract = available;
      this.canFill = canFill;
    }

    void calcToBalance(float targetRatio) {
      float targetAmount = capBank.getMaxEnergyStored() * targetRatio;
      toBalance = targetAmount - capBank.getEnergyStored();
      if (toBalance < 0) {
        toBalance = Math.max(toBalance, -canExtract);
      } else {
        toBalance = Math.min(toBalance, canFill);
      }

    }

  }

}
