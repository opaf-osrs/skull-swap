package com.skullswap;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class SkullSwapOverlay extends Overlay
{
	/**
	 * Extra height (in game units) added above the player's logical height to
	 * position the custom skull sprite. Tune in-game so our skull precisely
	 * covers the native skull. Start at 30 and adjust up/down.
	 */
	static final int SKULL_Z_OFFSET = 30;

	/**
	 * Additional world-space offset when the player also has an overhead prayer active.
	 * The prayer icon occupies screen space below the skull, so we need to push the
	 * skull higher to clear it. Tune in-game; ~25 is a good starting point.
	 */
	static final int PRAYER_OVERHEAD_EXTRA = 25;

	private final Client client;
	private final SkullSwapPlugin plugin;
	private final SkullSwapConfig config;

	@Inject
	SkullSwapOverlay(Client client, SkullSwapPlugin plugin, SkullSwapConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.mode() == SkullMode.OFF) return null;
		if (client.getGameState() != GameState.LOGGED_IN) return null;

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return null;

		for (Player player : wv.players())
		{
			if (player == null) continue;

			BufferedImage skull = plugin.getSkullForPlayer(player);
			if (skull == null) continue;

			int zOffset = player.getLogicalHeight() + SKULL_Z_OFFSET;
			if (player.getOverheadIcon() != null)
			{
				zOffset += PRAYER_OVERHEAD_EXTRA;
			}
			Point pos = player.getCanvasImageLocation(skull, zOffset);
			if (pos != null)
			{
				graphics.drawImage(skull, pos.getX(), pos.getY(), null);
			}
		}

		return null;
	}
}
