/*
 * Copyright (c) 2018, terminatusx <jbfleischman@gmail.com>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.wintertodt;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static api.AnimationID.CONSTRUCTION;
import static api.AnimationID.FIREMAKING;
import static api.AnimationID.FLETCHING_BOW_CUTTING;
import static api.AnimationID.IDLE;
import static api.AnimationID.LOOKING_INTO;
import static api.AnimationID.WOODCUTTING_3A_AXE;
import static api.AnimationID.WOODCUTTING_ADAMANT;
import static api.AnimationID.WOODCUTTING_BLACK;
import static api.AnimationID.WOODCUTTING_BRONZE;
import static api.AnimationID.WOODCUTTING_DRAGON;
import static api.AnimationID.WOODCUTTING_INFERNAL;
import static api.AnimationID.WOODCUTTING_IRON;
import static api.AnimationID.WOODCUTTING_MITHRIL;
import static api.AnimationID.WOODCUTTING_RUNE;
import static api.AnimationID.WOODCUTTING_STEEL;
import api.ChatMessageType;
import api.Client;
import api.InventoryID;
import api.Item;
import api.ItemContainer;
import static api.ItemID.BRUMA_KINDLING;
import static api.ItemID.BRUMA_ROOT;
import api.MessageNode;
import api.Player;
import api.events.AnimationChanged;
import api.events.ChatMessage;
import api.events.GameTick;
import api.events.ItemContainerChanged;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;

@PluginDescriptor(
	name = "Wintertodt",
	description = "Show helpful information for the Wintertodt boss",
	tags = {"minigame", "firemaking", "boss"}
)
@Slf4j
public class WintertodtPlugin extends Plugin
{
	private static final int WINTERTODT_REGION = 6462;

	@Inject
	private Notifier notifier;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WintertodtOverlay overlay;

	@Inject
	private WintertodtConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Getter(AccessLevel.PACKAGE)
	private WintertodtActivity currentActivity = WintertodtActivity.IDLE;

	@Getter(AccessLevel.PACKAGE)
	private int inventoryScore;

	@Getter(AccessLevel.PACKAGE)
	private int totalPotentialinventoryScore;

	@Getter(AccessLevel.PACKAGE)
	private int numLogs;

	@Getter(AccessLevel.PACKAGE)
	private int numKindling;

	@Getter(AccessLevel.PACKAGE)
	private boolean isInWintertodt;

	private Instant lastActionTime;

	@Provides
	WintertodtConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WintertodtConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		reset();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		reset();
	}

	private void reset()
	{
		inventoryScore = 0;
		totalPotentialinventoryScore = 0;
		numLogs = 0;
		numKindling = 0;
		currentActivity = WintertodtActivity.IDLE;
		lastActionTime = null;
	}

	private boolean isInWintertodtRegion()
	{
		if (client.getLocalPlayer() != null)
		{
			return client.getLocalPlayer().getWorldLocation().getRegionID() == WINTERTODT_REGION;
		}

		return false;
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (!isInWintertodtRegion())
		{
			if (isInWintertodt)
			{
				log.debug("Left Wintertodt!");
				reset();
			}

			isInWintertodt = false;
			return;
		}

		if (!isInWintertodt)
		{
			reset();
			log.debug("Entered Wintertodt!");
		}
		isInWintertodt = true;

		checkActionTimeout();
	}

	private void checkActionTimeout()
	{
		if (currentActivity == WintertodtActivity.IDLE)
		{
			return;
		}

		int currentAnimation = client.getLocalPlayer() != null ? client.getLocalPlayer().getAnimation() : -1;
		if (currentAnimation != IDLE || lastActionTime == null)
		{
			return;
		}

		Duration actionTimeout = Duration.ofSeconds(3);
		Duration sinceAction = Duration.between(lastActionTime, Instant.now());

		if (sinceAction.compareTo(actionTimeout) >= 0)
		{
			log.debug("Activity timeout!");
			currentActivity = WintertodtActivity.IDLE;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (!isInWintertodt)
		{
			return;
		}

		ChatMessageType chatMessageType = chatMessage.getType();

		if (chatMessageType != ChatMessageType.GAMEMESSAGE && chatMessageType != ChatMessageType.SPAM)
		{
			return;
		}

		MessageNode messageNode = chatMessage.getMessageNode();
		final WintertodtInterruptType interruptType;

		if (messageNode.getValue().startsWith("The cold of"))
		{
			interruptType = WintertodtInterruptType.COLD;
		}
		else if (messageNode.getValue().startsWith("The freezing cold attack"))
		{
			interruptType = WintertodtInterruptType.SNOWFALL;
		}
		else if (messageNode.getValue().startsWith("The brazier is broken and shrapnel"))
		{
			interruptType = WintertodtInterruptType.BRAZIER;
		}
		else if (messageNode.getValue().startsWith("You have run out of bruma roots"))
		{
			interruptType = WintertodtInterruptType.OUT_OF_ROOTS;
		}
		else if (messageNode.getValue().startsWith("Your inventory is too full"))
		{
			interruptType = WintertodtInterruptType.INVENTORY_FULL;
		}
		else if (messageNode.getValue().startsWith("You fix the brazier"))
		{
			interruptType = WintertodtInterruptType.FIXED_BRAZIER;
		}
		else if (messageNode.getValue().startsWith("You light the brazier"))
		{
			interruptType = WintertodtInterruptType.LIT_BRAZIER;
		}
		else if (messageNode.getValue().startsWith("The brazier has gone out."))
		{
			interruptType = WintertodtInterruptType.BRAZIER_WENT_OUT;
		}
		else
		{
			return;
		}

		boolean wasInterrupted = false;
		boolean wasDamaged = false;
		boolean neverNotify = false;

		switch (interruptType)
		{
			case COLD:
			case BRAZIER:
			case SNOWFALL:
				wasDamaged = true;

				// Recolor message for damage notification
				messageNode.setRuneLiteFormatMessage(ColorUtil.wrapWithColorTag(messageNode.getValue(), config.damageNotificationColor()));
				chatMessageManager.update(messageNode);
				client.refreshChat();

				// all actions except woodcutting are interrupted from damage
				if (currentActivity != WintertodtActivity.WOODCUTTING)
				{
					wasInterrupted = true;
				}

				break;
			case INVENTORY_FULL:
			case OUT_OF_ROOTS:
			case BRAZIER_WENT_OUT:
				wasInterrupted = true;
				break;
			case LIT_BRAZIER:
			case FIXED_BRAZIER:
				wasInterrupted = true;
				neverNotify = true;
				break;
		}

		if (!neverNotify)
		{
			boolean shouldNotify = false;

			switch (config.notifyCondition())
			{
				case ONLY_WHEN_INTERRUPTED:
					if (wasInterrupted)
					{
						shouldNotify = true;
					}
					break;
				case WHEN_DAMAGED:
					if (wasDamaged)
					{
						shouldNotify = true;
					}
					break;
				case EITHER:
					shouldNotify = true;
					break;
			}

			if (shouldNotify)
			{
				notifyInterrupted(interruptType, wasInterrupted);
			}
		}

		if (wasInterrupted)
		{
			currentActivity = WintertodtActivity.IDLE;
		}
	}

	private void notifyInterrupted(WintertodtInterruptType interruptType, boolean wasActivityInterrupted)
	{
		final StringBuilder str = new StringBuilder();

		str.append("Wintertodt: ");

		if (wasActivityInterrupted)
		{
			str.append(currentActivity.getActionString());
			str.append(" interrupted! ");
		}

		str.append(interruptType.getInterruptSourceString());

		String notification = str.toString();
		log.debug("Sending notification: {}", notification);
		notifier.notify(notification);
	}

	@Subscribe
	public void onAnimationChanged(final AnimationChanged event)
	{
		if (!isInWintertodt)
		{
			return;
		}

		final Player local = client.getLocalPlayer();

		if (event.getActor() != local)
		{
			return;
		}

		final int animId = local.getAnimation();
		switch (animId)
		{
			case WOODCUTTING_BRONZE:
			case WOODCUTTING_IRON:
			case WOODCUTTING_STEEL:
			case WOODCUTTING_BLACK:
			case WOODCUTTING_MITHRIL:
			case WOODCUTTING_ADAMANT:
			case WOODCUTTING_RUNE:
			case WOODCUTTING_DRAGON:
			case WOODCUTTING_INFERNAL:
			case WOODCUTTING_3A_AXE:
				setActivity(WintertodtActivity.WOODCUTTING);
				break;

			case FLETCHING_BOW_CUTTING:
				setActivity(WintertodtActivity.FLETCHING);
				break;

			case LOOKING_INTO:
				setActivity(WintertodtActivity.FEEDING_BRAZIER);
				break;

			case FIREMAKING:
				setActivity(WintertodtActivity.LIGHTING_BRAZIER);
				break;

			case CONSTRUCTION:
				setActivity(WintertodtActivity.FIXING_BRAZIER);
				break;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final ItemContainer container = event.getItemContainer();

		if (!isInWintertodt || container != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}

		final Item[] inv = container.getItems();

		inventoryScore = 0;
		totalPotentialinventoryScore = 0;
		numLogs = 0;
		numKindling = 0;

		for (Item item : inv)
		{
			inventoryScore += getPoints(item.getId());
			totalPotentialinventoryScore += getPotentialPoints(item.getId());

			switch (item.getId())
			{
				case BRUMA_ROOT:
					++numLogs;
					break;
				case BRUMA_KINDLING:
					++numKindling;
					break;
			}
		}

		//If we're currently fletching but there are no more logs, go ahead and abort fletching immediately
		if (numLogs == 0 && currentActivity == WintertodtActivity.FLETCHING)
		{
			currentActivity = WintertodtActivity.IDLE;
		}
		//Otherwise, if we're currently feeding the brazier but we've run out of both logs and kindling, abort the feeding activity
		else if (numLogs == 0 && numKindling == 0 && currentActivity == WintertodtActivity.FEEDING_BRAZIER)
		{
			currentActivity = WintertodtActivity.IDLE;
		}
	}

	private void setActivity(WintertodtActivity action)
	{
		currentActivity = action;
		lastActionTime = Instant.now();
	}

	private static int getPoints(int id)
	{
		switch (id)
		{
			case BRUMA_ROOT:
				return 10;
			case BRUMA_KINDLING:
				return 25;
			default:
				return 0;
		}
	}

	private static int getPotentialPoints(int id)
	{
		switch (id)
		{
			case BRUMA_ROOT:
			case BRUMA_KINDLING:
				return 25;
			default:
				return 0;
		}
	}
}