package dev.maqueta.client.screen;

import dev.maqueta.client.config.MaquetaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** In-game settings. Every change applies live and is saved on close. */
public final class ConfigScreen extends Screen {
	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("maqueta.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MaquetaConfig c = MaquetaConfig.get();
		int col0 = width / 2 - 155, col1 = width / 2 + 5;
		int y = 40;
		toggle(col0, y, "maqueta.config.minimap", () -> c.minimapEnabled, v -> c.minimapEnabled = v);
		cycle(col1, y, () -> Component.translatable("maqueta.config.anchor", Component.translatable(c.anchor.key())), () -> c.anchor = c.anchor.next());
		y += 24;
		cycle(col0, y, () -> Component.translatable("maqueta.config.size", c.minimapSize), () -> c.minimapSize = c.minimapSize >= 220 ? 88 : c.minimapSize + 22);
		cycle(col1, y, () -> Component.translatable("maqueta.config.zoom", Math.round(c.minimapDistance)), () -> c.minimapDistance = c.minimapDistance >= 200 ? 32 : c.minimapDistance * 1.4f);
		y += 24;
		cycle(col0, y, () -> Component.translatable("maqueta.config.offset", c.offsetX, c.offsetY), () -> {
			c.offsetX = c.offsetX >= 30 ? 0 : c.offsetX + 6;
			c.offsetY = c.offsetX;
		});
		cycle(col1, y, () -> Component.translatable("maqueta.config.tilt", Math.round(c.minimapPitchDeg)), () -> c.minimapPitchDeg = c.minimapPitchDeg >= 85 ? 35 : c.minimapPitchDeg + 10);
		y += 24;
		toggle(col0, y, "maqueta.config.rotate", () -> c.rotateWithPlayer, v -> c.rotateWithPlayer = v);
		toggle(col1, y, "maqueta.config.compass", () -> c.showCompass, v -> c.showCompass = v);
		y += 24;
		toggle(col0, y, "maqueta.config.trail", () -> c.showTrail, v -> c.showTrail = v);
		toggle(col1, y, "maqueta.config.entities", () -> c.showEntities, v -> c.showEntities = v);
		y += 24;
		toggle(col0, y, "maqueta.config.sunmoon", () -> c.showSunMoon, v -> c.showSunMoon = v);
		toggle(col1, y, "maqueta.config.clock12", () -> c.twelveHourClock, v -> c.twelveHourClock = v);
		y += 24;
		toggle(col0, y, "maqueta.config.coords", () -> c.showCoordinates, v -> c.showCoordinates = v);
		toggle(col1, y, "maqueta.config.autotilt", () -> c.autoTilt, v -> c.autoTilt = v);
		y += 24;
		toggle(col0, y, "maqueta.config.reducemotion", () -> c.reduceMotion, v -> c.reduceMotion = v);
		cycle(col1, y, () -> Component.translatable("maqueta.config.quality", String.format(Locale.ROOT, "%.1fx", c.supersampling)),
			() -> c.supersampling = c.supersampling >= 2f ? 1f : c.supersampling + 0.5f);
		y += 24;
		toggle(col0, y, "maqueta.config.safemode", () -> c.safeMode, v -> c.safeMode = v);
		cycle(col1, y, () -> Component.translatable("maqueta.config.radius", c.renderRadiusChunks), () -> c.renderRadiusChunks = c.renderRadiusChunks >= 128 ? 16 : c.renderRadiusChunks * 2);
		y += 32;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 100, y, 200, 20).build());
	}

	private void toggle(int x, int y, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
		Button[] holder = new Button[1];
		holder[0] = Button.builder(label(key, get.get()), b -> {
			set.accept(!get.get());
			b.setMessage(label(key, get.get()));
		}).bounds(x, y, 150, 20).build();
		addRenderableWidget(holder[0]);
	}

	private static Component label(String key, boolean on) {
		return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private void cycle(int x, int y, Supplier<Component> text, Runnable next) {
		addRenderableWidget(Button.builder(text.get(), b -> {
			next.run();
			b.setMessage(text.get());
		}).bounds(x, y, 150, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 16, 0xFFF2F4F8);
	}

	@Override
	public void onClose() {
		MaquetaConfig.get().save();
		Minecraft.getInstance().gui.setScreen(parent);
	}
}
