package dev.maqueta.client.screen;

import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.marker.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Create or edit a waypoint: name, colour, target, delete. */
public final class WaypointScreen extends Screen {
	private final Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	private EditBox name;
	private int colorIndex;

	public WaypointScreen(Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "maqueta.waypoint.new" : "maqueta.waypoint.edit"));
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		for (int i = 0; i < Waypoint.COLORS.length; i++) if (Waypoint.COLORS[i] == waypoint.color) colorIndex = i;
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height / 2 - 40;
		name = new EditBox(font, cx - 100, y, 200, 20, Component.translatable("maqueta.waypoint.name"));
		name.setMaxLength(32);
		name.setValue(waypoint.name);
		addRenderableWidget(name);
		setInitialFocus(name);
		addRenderableWidget(Button.builder(Component.translatable("maqueta.waypoint.color"), b -> {
			colorIndex = (colorIndex + 1) % Waypoint.COLORS.length;
			waypoint.color = Waypoint.COLORS[colorIndex];
		}).bounds(cx - 100, y + 26, 98, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("maqueta.waypoint.target"), b -> {
			save();
			MapSession s = MapSession.get();
			if (s != null) s.waypoints.setTarget(waypoint);
			onClose();
		}).bounds(cx + 2, y + 26, 98, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("maqueta.waypoint.save"), b -> {
			save();
			onClose();
		}).bounds(cx - 100, y + 52, 98, 20).build());
		addRenderableWidget(Button.builder(Component.translatable(isNew ? "gui.cancel" : "maqueta.waypoint.delete"), b -> {
			if (!isNew) {
				MapSession s = MapSession.get();
				if (s != null) s.waypoints.remove(waypoint);
			}
			onClose();
		}).bounds(cx + 2, y + 52, 98, 20).build());
	}

	private void save() {
		waypoint.name = name.getValue().isBlank() ? "?" : name.getValue().trim();
		MapSession s = MapSession.get();
		if (s == null) return;
		if (isNew && !s.waypoints.all().contains(waypoint)) s.waypoints.add(waypoint);
		else s.waypoints.save();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int cx = width / 2, y = height / 2 - 62;
		g.centeredText(font, title, cx, y, 0xFFF2F4F8);
		String pos = String.format(Locale.ROOT, "%d, %d, %d", waypoint.x, waypoint.y, waypoint.z);
		g.centeredText(font, pos, cx, y + 11, 0xFFAEB7C6);
		g.fill(cx + 104, height / 2 - 40, cx + 124, height / 2 - 20, 0xFF000000 | waypoint.color);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(parent);
	}
}
