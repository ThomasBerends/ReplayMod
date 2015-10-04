package com.replaymod.extras;

import com.replaymod.core.ReplayMod;
import com.replaymod.replay.events.ReplayOpenEvent;
import com.replaymod.replay.gui.overlay.GuiReplayOverlay;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiElement;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.GuiTexturedButton;
import de.johni0702.minecraft.gui.layout.CustomLayout;
import de.johni0702.minecraft.gui.layout.GridLayout;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.LayoutData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class HotkeyButtons implements Extra {
    private ReplayMod mod;

    @Override
    public void register(ReplayMod mod) throws Exception {
        this.mod = mod;

        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void postReplayOpen(ReplayOpenEvent.Post event) {
        GuiReplayOverlay overlay = event.getReplayHandler().getOverlay();
        new Gui(mod, overlay);
    }

    public static final class Gui {
        private final GuiTexturedButton toggleButton;
        private final GridLayout panelLayout;
        private final GuiPanel panel;

        private boolean open;

        public Gui(ReplayMod mod, GuiReplayOverlay overlay) {
            toggleButton = new GuiTexturedButton(overlay).setSize(20, 20)
                    .setTexture(ReplayMod.TEXTURE, ReplayMod.TEXTURE_SIZE).setTexturePosH(0, 0)
                    .onClick(new Runnable() {
                        @Override
                        public void run() {
                            open = !open;
                        }
                    });

            panel = new GuiPanel(overlay) {
                @Override
                public Collection<GuiElement> getChildren() {
                    return open ? super.getChildren() : Collections.<GuiElement>emptyList();
                }

                @Override
                public Map<GuiElement, LayoutData> getElements() {
                    return open ? super.getElements() : Collections.<GuiElement, LayoutData>emptyMap();
                }
            }.setLayout(panelLayout = new GridLayout().setSpacingX(5).setSpacingY(5).setColumns(1));

            for (final KeyBinding keyBinding : mod.getKeyBindingRegistry().getKeyBindings().values()) {
                String keyName = "???";
                try {
                    keyName = Keyboard.getKeyName(keyBinding.getKeyCode());
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Apparently windows likes to press strange keys, see https://www.replaymod.com/forum/thread/55
                }
                panel.addElements(null, new GuiPanel().setSize(150, 20).setLayout(new HorizontalLayout().setSpacing(2))
                        .addElements(new HorizontalLayout.Data(0.5),
                                new GuiButton().setSize(20, 20).setLabel(keyName).onClick(new Runnable() {
                                    @Override
                                    public void run() {
                                        keyBinding.pressTime++;
                                        FMLCommonHandler.instance().fireKeyInput();
                                    }
                                }),
                                new GuiLabel().setI18nText(keyBinding.getKeyDescription())
                        ));
            }

            overlay.setLayout(new CustomLayout<GuiReplayOverlay>(overlay.getLayout()) {
                @Override
                protected void layout(GuiReplayOverlay container, int width, int height) {
                    panelLayout.setColumns((width - 10) / 155);
                    size(panel, panel.getMinSize());

                    pos(toggleButton, 5, height - 25);
                    pos(panel, 5, y(toggleButton) - 5 - height(panel));
                }
            });
        }
    }
}