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
	 * Additional world-space offset when the player also has an overhead prayer active.
	 * The prayer icon occupies screen space below the skull, so we push the skull higher.
	 */
	private static final int PRAYER_OVERHEAD_EXTRA = 25;

	private final Client client;
	private final SkullSwapPlugin plugin;
	private final SkullSwapConfig config;

	@Inject
	SkullSwapOverlay(Client client, SkullSwapPlugin plugin, SkullSwapConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		// ALWAYS_ON_TOP renders after the native skull — required to cover it.
		// ABOVE_SCENE renders before it and cannot cover it.
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.mode() == SkullMode.OFF || config.mode() == SkullMode.HIDE) return null;
		if (client.getGameState() != GameState.LOGGED_IN) return null;

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return null;

		for (Player player : wv.players())
		{
			if (player == null) continue;

			BufferedImage skull = plugin.getSkullForPlayer(player);
			if (skull == null) continue;

			int zOffset = player.getLogicalHeight() + config.skullZOffset();
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
