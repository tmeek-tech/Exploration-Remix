package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.menu.RuneBlockMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ERMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ExplorationRemix.MOD_ID);
    private ERMenus() {}

    public static final RegistryObject<MenuType<RuneBlockMenu>> RUNE_BLOCK = MENUS.register("rune_block",
            () -> IForgeMenuType.create(RuneBlockMenu::new));
}
