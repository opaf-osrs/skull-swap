package com.skullswap;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("skullswap")
public interface SkullSwapConfig extends Config
{
	@ConfigItem(
		keyName = "mode",
		name = "Mode",
		description = "Which skull replacement mode to use",
		position = 0
	)
	default SkullMode mode()
	{
		return SkullMode.OFF;
	}

	@Range(min = 1, max = 25)
	@ConfigItem(
		keyName = "selectedSkull",
		name = "Selected skull (1–25)",
		description = "Which skull image to use in Replace and Manual modes",
		position = 1
	)
	default int selectedSkull()
	{
		return 1;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "randomChance",
		name = "Random chance (%)",
		description = "Percentage of nearby players that receive a cosmetic skull in Random Cosmetic mode",
		position = 2
	)
	default int randomChance()
	{
		return 50;
	}

	@Range(min = 0, max = 80)
	@ConfigItem(
		keyName = "skullZOffset",
		name = "Skull Z offset",
		description = "Vertical offset (in game units) used to position the custom skull over the player's head. Increase if the skull appears too low.",
		position = 3
	)
	default int skullZOffset()
	{
		return 40;
	}
}
