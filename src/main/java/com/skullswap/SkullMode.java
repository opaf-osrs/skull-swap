package com.skullswap;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SkullMode
{
	OFF("Off"),
	HIDE("Hide"),
	REPLACE_SINGLE("Replace (single)"),
	REPLACE_RANDOM("Replace (random session)"),
	RANDOM_COSMETIC("Random cosmetic (all players)"),
	MANUAL("Manual assign");

	private final String displayName;

	@Override
	public String toString()
	{
		return displayName;
	}
}
