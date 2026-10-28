package com.explorationremix.client;

import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneGrammar;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.RuneTool;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Each tool's tooltip shows its sentences in plain words, with cooldowns, and whether its runes are alive. */
public final class RuneTooltips {
    private RuneTooltips() {}

    public static void append(ItemStack stack, List<Component> tooltip) {
        if (!RuneTool.canHoldRunes(stack)) return;
        List<ResourceLocation> ids = RuneTool.runes(stack);
        String att = RuneTool.attunement(stack);
        if (ids.isEmpty() && att.isEmpty()) return;
        long run = ClientRunState.runId;
        boolean dead = !ids.isEmpty() && (run == 0L || RuneTool.runStamp(stack) != run);
        if (!att.isEmpty()) tooltip.add(Component.translatable("rune.explorationremix.tooltip.attuned", Component.translatable("attunement.explorationremix." + att)).withStyle(ChatFormatting.AQUA));
        if (ids.isEmpty()) return;
        if (dead) {
            tooltip.add(Component.translatable("rune.explorationremix.tooltip.dead").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("rune.explorationremix.tooltip.slots", ids.size(), RuneTool.slots(stack)).withStyle(ChatFormatting.GRAY));
        List<Rune> live = new ArrayList<>();
        for (ResourceLocation id : ids) { Rune r = RuneRegistry.get(id); if (r != null) live.add(r); }
        RuneGrammar.Parsed parsed = RuneGrammar.parse(live);
        for (RuneGrammar.Sentence s : parsed.sentences()) {
            MutableComponent line = Component.translatable(s.trigger().translationKey()).withStyle(s.trigger().formatting()).append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY));
            boolean first = true;
            for (RuneGrammar.Clause c : s.clauses()) {
                if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                first = false;
                for (Rune m : c.modifiers()) line.append(Component.translatable(m.translationKey()).withStyle(m.formatting())).append(" ");
                line.append(Component.translatable(c.effect().translationKey()).withStyle(c.effect().formatting()));
            }
            line.append(Component.literal(" ").append(Component.translatable("rune.explorationremix.tooltip.cooldown", String.format("%.1f", s.cooldownTicks() / 20.0)).withStyle(ChatFormatting.DARK_GRAY)));
            tooltip.add(line);
        }
        if (!parsed.passives().isEmpty()) {
            MutableComponent line = Component.empty();
            boolean first = true;
            for (Rune p : parsed.passives()) {
                if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                first = false;
                line.append(Component.translatable(p.translationKey()).withStyle(p.formatting()));
            }
            tooltip.add(Component.translatable("rune.explorationremix.tooltip.passives", line).withStyle(ChatFormatting.GREEN));
        }
        for (Rune o : parsed.orphans()) tooltip.add(Component.translatable(o.translationKey()).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH));
    }
}
