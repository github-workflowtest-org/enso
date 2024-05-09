import { createContextStore } from '@/providers'
import type { GraphNavigator } from '@/providers/graphNavigator'
import { watch, type WatchSource } from 'vue'

export { injectFn as injectInteractionHandler, provideFn as provideInteractionHandler }
const { provideFn, injectFn } = createContextStore(
  'Interaction handler',
  () => new InteractionHandler(),
)

export class InteractionHandler {
  private currentInteraction: Interaction | undefined = undefined

  isActive(interaction: Interaction | undefined): interaction is Interaction {
    return interaction != null && interaction === this.currentInteraction
  }

  /** Automatically activate specified interaction any time a specified condition becomes true. */
  setWhen(active: WatchSource<boolean>, interaction: Interaction) {
    watch(active, (active) => {
      if (active) {
        this.setCurrent(interaction)
      } else {
        this.end(interaction)
      }
    })
  }

  setCurrent(interaction: Interaction | undefined) {
    if (!this.isActive(interaction)) {
      this.currentInteraction?.cancel?.()
      this.currentInteraction = interaction
    }
  }

  getCurrent(): Interaction | undefined {
    return this.currentInteraction
  }

  /** Unset the current interaction, if it is the specified instance. */
  end(interaction: Interaction) {
    if (this.isActive(interaction)) this.currentInteraction = undefined
  }

  /** Cancel the current interaction, if it is the specified instance. */
  cancel(interaction: Interaction) {
    if (this.isActive(interaction)) this.setCurrent(undefined)
  }

  handleCancel(): boolean {
    const hasCurrent = this.currentInteraction != null
    if (hasCurrent) this.setCurrent(undefined)
    return hasCurrent
  }

  handlePointerEvent<HandlerName extends keyof Interaction>(
    event: PointerEvent,
    handlerName: Interaction[HandlerName] extends InteractionEventHandler | undefined ? HandlerName
    : never,
    graphNavigator: GraphNavigator,
  ): boolean {
    if (!this.currentInteraction) return false
    const handler = this.currentInteraction[handlerName]
    if (!handler) return false
    const handled = handler.bind(this.currentInteraction)(event, graphNavigator) !== false
    if (handled) {
      event.stopImmediatePropagation()
      event.preventDefault()
    }
    return handled
  }
}

type InteractionEventHandler = (event: PointerEvent, navigator: GraphNavigator) => boolean | void

export interface Interaction {
  cancel(): void
  /** Uses a `capture` event handler to allow an interaction to respond to clicks over any element. */
  pointerdown?: InteractionEventHandler
  /** Uses a `capture` event handler to allow an interaction to respond to mouse button release
   * over any element. It is useful for interactions happening during mouse press (like dragging
   * edges) */
  pointerup?: InteractionEventHandler
}
