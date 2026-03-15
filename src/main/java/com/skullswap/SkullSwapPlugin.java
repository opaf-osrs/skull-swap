package com.skullswap;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.RuneLite;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Provider;
import java.awt.image.BufferedImage;
import java.io.File;
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
	private static final int SKULL_COUNT = 25;

	/** Folder where users drop their own skull PNGs: ~/.runelite/skullswap/skull_01.png … */
	static final File CUSTOM_SKULL_DIR = new File(RuneLite.RUNELITE_DIR, "skullswap");

	@Inject private Client client;
	@Inject private ConfigManager configManager;
	@Inject private SkullSwapConfig config;
	@Inject private OverlayManager overlayManager;
	@Inject private SkullSwapOverlay overlay;
	@Inject private Provider<MenuManager> menuManager;

	private final Random random = new Random();

	/** Custom skull images loaded from /skulls/skull_01.png … skull_25.png. */
	final BufferedImage[] skullImages = new BufferedImage[SKULL_COUNT];

	/**
	 * Ground-truth skull state tracked from network events BEFORE our per-tick
	 * setSkullIcon(-1) interference. player name → native skull icon value.
	 * Only contains players who actually have a skull.
	 */
	final Map<String, Integer> realSkulledPlayers = new HashMap<>();

	/** Skull index (0–24) for the whole session in REPLACE_RANDOM mode. Re-rolled on LOGGED_IN. */
	int sessionSkullIndex = 0;

	/**
	 * Stable cosmetic skull assignments for RANDOM_COSMETIC mode.
	 * player name → skull index (0–24) or -1 (no cosmetic skull assigned this session).
	 * Cleared on LOGGED_IN.
	 */
	final Map<String, Integer> randomAssignments = new HashMap<>();

	/**
	 * Manual skull assignments for MANUAL mode.
	 * player name → skull index (0–24).
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
		// Seed default so getConfiguration never returns null for enum config items.
		if (configManager.getConfiguration(CONFIG_GROUP, "mode") == null)
		{
			configManager.setConfiguration(CONFIG_GROUP, "mode", SkullMode.OFF.name());
		}

		loadSkullImages();
		CUSTOM_SKULL_DIR.mkdirs();

		menuManager.get().addPlayerMenuItem(ASSIGN_OPTION);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		menuManager.get().removePlayerMenuItem(ASSIGN_OPTION);
		menuManager.get().removePlayerMenuItem(REMOVE_OPTION);
		overlayManager.remove(overlay);
		restoreSkullIcons();
		realSkulledPlayers.clear();
		randomAssignments.clear();
		manualAssignments.clear();
		sessionSkullIndex = 0;
	}

	// ── Events ────────────────────────────────────────────────────────────────

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup())) return;
		if ("mode".equals(event.getKey()) && config.mode() == SkullMode.OFF)
		{
			restoreSkullIcons();
		}
		if ("useCustomSkulls".equals(event.getKey()) || "selectedCustomSkull".equals(event.getKey()))
		{
			loadSkullImages();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			sessionSkullIndex = random.nextInt(SKULL_COUNT);
			randomAssignments.clear();
			realSkulledPlayers.clear();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			randomAssignments.clear();
			manualAssignments.clear();
			realSkulledPlayers.clear();
			sessionSkullIndex = 0;
		}
	}

	/**
	 * Track real skull state from network data BEFORE we interfere with setSkullIcon.
	 * PlayerSpawned fires when a player enters the scene — read their native skull here.
	 */
	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		trackPlayerSkull(event.getPlayer());
	}

	/**
	 * PlayerChanged fires when the server sends an update for an existing player,
	 * including skull changes. This is the ground-truth update we rely on.
	 */
	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		trackPlayerSkull(event.getPlayer());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		Player player = event.getPlayer();
		if (player.getName() != null)
		{
			realSkulledPlayers.remove(player.getName());
		}
	}

	private void trackPlayerSkull(Player player)
	{
		if (player.getName() == null) return;
		int icon = player.getSkullIcon();
		if (icon != -1)
		{
			realSkulledPlayers.put(player.getName(), icon);
		}
		else
		{
			realSkulledPlayers.remove(player.getName());
		}
	}

	/**
	 * Hide native skulls every tick. Must be called every tick because the game
	 * resets skullIcon from network data constantly. setSkullIcon(-1) is the ONLY
	 * method confirmed to work — getSpriteOverrides does NOT affect skull rendering.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (config.mode() == SkullMode.OFF) return;
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return;

		for (Player player : wv.players())
		{
			if (player != null && player.getSkullIcon() != -1)
			{
				player.setSkullIcon(-1);
			}
		}
	}

	/** Loads skull images from the custom folder or the built-in jar resources. */
	void loadSkullImages()
	{
		boolean custom = config.useCustomSkulls();
		int loaded = 0;
		for (int i = 0; i < SKULL_COUNT; i++)
		{
			String filename = "skull_" + String.format("%02d", i + 1) + ".png";
			try
			{
				BufferedImage img = null;
				if (custom)
				{
					File f = new File(CUSTOM_SKULL_DIR, filename);
					if (f.exists())
					{
						img = ImageIO.read(f);
					}
				}
				if (img == null)
				{
					img = ImageUtil.loadImageResource(getClass(), "/skulls/" + filename);
				}
				if (img != null)
				{
					BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
					argb.getGraphics().drawImage(img, 0, 0, null);
					skullImages[i] = argb;
					loaded++;
				}
				else
				{
					skullImages[i] = null;
				}
			}
			catch (Exception e)
			{
				skullImages[i] = null;
				log.warn("SkullSwap: failed to load {}", filename, e);
			}
		}
		log.info("SkullSwap: loaded {}/{} skull images ({})", loaded, SKULL_COUNT, custom ? "custom" : "built-in");
	}

	/** Restore real skull icons for all visible players — called on shutdown or mode → OFF. */
	private void restoreSkullIcons()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) return;

		for (Player player : wv.players())
		{
			if (player == null || player.getName() == null) continue;
			Integer original = realSkulledPlayers.get(player.getName());
			player.setSkullIcon(original != null ? original : -1);
		}
	}

	/**
	 * Conditionally injects "Remove skull" when right-clicking a player who already
	 * has a manual assignment.
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
	 *
	 * Uses realSkulledPlayers (not player.getSkullIcon()) for REPLACE modes because
	 * we set skullIcon to -1 ourselves every tick — the field is no longer reliable.
	 */
	BufferedImage getSkullForPlayer(Player player)
	{
		if (player == null) return null;
		String name = player.getName();

		int selected = config.useCustomSkulls() ? config.selectedCustomSkull() : config.selectedSkull();

		switch (config.mode())
		{
			case REPLACE_SINGLE:
				if (!realSkulledPlayers.containsKey(name)) return null;
				return safeGet(selected - 1);

			case REPLACE_RANDOM:
				if (!realSkulledPlayers.containsKey(name)) return null;
				return safeGet(sessionSkullIndex);

			case RANDOM_COSMETIC:
			{
				if (name == null) return null;
				if (!randomAssignments.containsKey(name))
				{
					int idx = (random.nextInt(100) < config.randomChance())
						? random.nextInt(SKULL_COUNT)
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
