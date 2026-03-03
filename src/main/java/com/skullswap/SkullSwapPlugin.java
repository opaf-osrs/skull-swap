package com.skullswap;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Provider;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@PluginDescriptor(
	name = "Skull Swap",
	description = "Cosmetically replace overhead skull icons with custom PNG images.",
	tags = {"skull", "cosmetic", "pvp", "overhead", "icon", "custom"}
)
public class SkullSwapPlugin extends Plugin
{
	private static final String ASSIGN_OPTION = "Assign skull";
	private static final String REMOVE_OPTION = "Remove skull";

	private static final String CONFIG_GROUP = "skullswap";

	@Inject private Client client;
	@Inject private ConfigManager configManager;
	@Inject private SkullSwapConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private SkullSwapOverlay overlay;
	@Inject private Provider<MenuManager> menuManager;

	private final Random random = new Random();

	/** The 16 custom skull images, loaded from /skulls/skull_01.png … skull_16.png. */
	final BufferedImage[] skullImages = new BufferedImage[16];

	/**
	 * Skull index (0–15) assigned for the whole session in REPLACE_RANDOM mode.
	 * Re-randomised on each LOGGED_IN transition.
	 */
	int sessionSkullIndex = 0;

	/**
	 * Stable cosmetic skull assignments for RANDOM_COSMETIC mode.
	 * player name → skull index (0–15) or -1 (no cosmetic skull assigned).
	 * Cleared on LOGGED_IN.
	 */
	final Map<String, Integer> randomAssignments = new HashMap<>();

	/**
	 * Manual skull assignments for MANUAL mode.
	 * player name → skull index (0–15).
	 * Cleared on LOGIN_SCREEN.
	 */
	final Map<String, Integer> manualAssignments = new HashMap<>();

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	@Provides
	SkullSwapConfig provideConfig(ConfigManager cm)
	{
		return cm.getConfig(SkullSwapConfig.class);
	}

	@Override
	protected void startUp()
	{
		// ConfigPanel.createComboBox calls Enum.valueOf(type, configManager.getConfiguration(...))
		// with no null-guard. If no value is stored yet (first run) that's an NPE.
		// Seed the default so getConfiguration never returns null for enum config items.
		if (configManager.getConfiguration(CONFIG_GROUP, "mode") == null)
		{
			configManager.setConfiguration(CONFIG_GROUP, "mode", SkullMode.OFF.name());
		}

		int loaded = 0;
		for (int i = 0; i < 16; i++)
		{
			String path = "/skulls/skull_" + String.format("%02d", i + 1) + ".png";
			try
			{
				skullImages[i] = ImageUtil.loadImageResource(getClass(), path);
				if (skullImages[i] != null) loaded++;
			}
			catch (Exception e)
			{
				log.warn("SkullSwap: failed to load {}", path, e);
			}
		}
		log.info("SkullSwap: loaded {}/16 skull images", loaded);
		menuManager.get().addPlayerMenuItem(ASSIGN_OPTION);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		menuManager.get().removePlayerMenuItem(ASSIGN_OPTION);
		menuManager.get().removePlayerMenuItem(REMOVE_OPTION);
		overlayManager.remove(overlay);
		randomAssignments.clear();
		manualAssignments.clear();
		sessionSkullIndex = 0;
	}

	// ── Events ────────────────────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			sessionSkullIndex = random.nextInt(16);
			randomAssignments.clear();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			randomAssignments.clear();
			manualAssignments.clear();
			sessionSkullIndex = 0;
		}
	}

	/**
	 * Conditionally injects "Remove skull" when right-clicking a player who already
	 * has a manual assignment. "Assign skull" is always present via MenuManager.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (config.mode() != SkullMode.MANUAL) return;

		for (MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() != MenuAction.RUNELITE_PLAYER) continue;
			if (!ASSIGN_OPTION.equals(entry.getOption())) continue;

			Player player = entry.getPlayer();
			if (player == null) continue;
			String name = player.getName();
			if (name == null) return;

			if (manualAssignments.containsKey(name))
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(REMOVE_OPTION)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE);
			}
			return;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();

		// "Assign skull" — added by MenuManager, type is RUNELITE_PLAYER, getPlayer() works.
		if (ASSIGN_OPTION.equals(option) && event.getMenuAction() == MenuAction.RUNELITE_PLAYER)
		{
			if (config.mode() != SkullMode.MANUAL) return;
			Player player = event.getMenuEntry().getPlayer();
			if (player == null) return;
			String name = player.getName();
			if (name == null) return;
			manualAssignments.put(name, config.selectedSkull() - 1);
			log.info("SkullSwap: assigned skull {} to '{}'", config.selectedSkull(), name);
			return;
		}

		// "Remove skull" — added by onMenuOpened, type is RUNELITE, use target string.
		if (REMOVE_OPTION.equals(option) && event.getMenuAction() == MenuAction.RUNELITE)
		{
			String name = Text.removeTags(event.getMenuTarget()).trim();
			if (name.isEmpty()) return;
			manualAssignments.remove(name);
			log.info("SkullSwap: removed skull from '{}'", name);
		}
	}

	// ── Skull selection ───────────────────────────────────────────────────────

	/**
	 * Returns the custom skull image to draw for the given player, or null if none.
	 * Called by {@link SkullSwapOverlay} each frame.
	 */
	BufferedImage getSkullForPlayer(Player player)
	{
		if (player == null) return null;
		String name = player.getName();

		switch (config.mode())
		{
			case REPLACE_SINGLE:
				if (player.getSkullIcon() == -1) return null;
				return safeGet(config.selectedSkull() - 1);

			case REPLACE_RANDOM:
				if (player.getSkullIcon() == -1) return null;
				return safeGet(sessionSkullIndex);

			case RANDOM_COSMETIC:
			{
				if (name == null) return null;
				if (!randomAssignments.containsKey(name))
				{
					// Roll once per session; -1 means "no cosmetic skull"
					int idx = (random.nextInt(100) < config.randomChance())
						? random.nextInt(16)
						: -1;
					randomAssignments.put(name, idx);
				}
				int idx = randomAssignments.get(name);
				return idx < 0 ? null : safeGet(idx);
			}

			case MANUAL:
			{
				if (name == null) return null;
				Integer idx = manualAssignments.get(name);
				return idx == null ? null : safeGet(idx);
			}

			default:
				return null;
		}
	}

	private BufferedImage safeGet(int idx)
	{
		if (idx < 0 || idx >= skullImages.length) return null;
		return skullImages[idx];
	}
}
