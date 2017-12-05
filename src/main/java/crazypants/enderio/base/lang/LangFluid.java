package crazypants.enderio.base.lang;

import javax.annotation.Nonnull;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

public final class LangFluid {

  public static @Nonnull String MB(int amount) {
    return Lang.FLUID_AMOUNT.get(amount);
  }

  public static @Nonnull String MB(int amount, int total) {
    return Lang.FLUID_LEVEL.get(amount, total);
  }

  public static @Nonnull String MB(FluidStack amount, int total) {
    return Lang.FLUID_LEVEL_NAME.get(amount.amount, total, amount.getLocalizedName());
  }

  public static @Nonnull String MB(int amount, int total, Fluid fluid) {
    return MB(new FluidStack(fluid, amount), total);
  }

  public static @Nonnull String MB(int amount, Fluid fluid) {
    return Lang.FLUID_AMOUNT_NAME.get(amount, new FluidStack(fluid, amount).getLocalizedName());
  }

  public static @Nonnull String MBt(int amount) {
    return Lang.FLUID_TICKPER.get(amount);
  }

  public static @Nonnull String toCapactityString(IFluidTank tank) {
    if (tank == null) {
      return MB(0, 0);
    }
    return MB(tank.getFluidAmount(), tank.getCapacity());
  }

}