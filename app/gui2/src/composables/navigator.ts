/** @file A Vue composable for panning and zooming a DOM element. */

import { useApproach, useApproachVec } from '@/composables/animation'
import { PointerButtonMask, useEvent, usePointer, useResizeObserver } from '@/composables/events'
import type { KeyboardComposable } from '@/composables/keyboard'
import { Rect } from '@/util/data/rect'
import { Vec2 } from '@/util/data/vec2'
import { computed, proxyRefs, readonly, shallowRef, toRef, type Ref } from 'vue'

type ScaleRange = readonly [number, number]
const PAN_AND_ZOOM_DEFAULT_SCALE_RANGE: ScaleRange = [0.1, 1]
const ZOOM_LEVELS = [
  0.1, 0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0,
]
const DEFAULT_SCALE_RANGE: ScaleRange = [Math.min(...ZOOM_LEVELS), Math.max(...ZOOM_LEVELS)]
const ZOOM_LEVELS_REVERSED = [...ZOOM_LEVELS].reverse()
/** The fraction of the next zoom level.
 * If we are that close to next zoom level, we should choose the next one instead
 * to avoid small unnoticeable changes to zoom. */
const ZOOM_SKIP_THRESHOLD = 0.05

function elemRect(target: Element | undefined): Rect {
  if (target != null && target instanceof Element)
    return Rect.FromDomRect(target.getBoundingClientRect())
  return Rect.Zero
}

export type NavigatorComposable = ReturnType<typeof useNavigator>
export function useNavigator(viewportNode: Ref<Element | undefined>, keyboard: KeyboardComposable) {
  const size = useResizeObserver(viewportNode)
  const targetCenter = shallowRef<Vec2>(Vec2.Zero)
  const center = useApproachVec(targetCenter, 100, 0.02)

  const targetScale = shallowRef(1)
  const scale = useApproach(targetScale)
  const panPointer = usePointer((pos) => {
    scrollTo(center.value.addScaled(pos.delta, -1 / scale.value))
  }, PointerButtonMask.Auxiliary)

  function eventScreenPos(e: { clientX: number; clientY: number }): Vec2 {
    return new Vec2(e.clientX, e.clientY)
  }

  function clientToScenePos(clientPos: Vec2): Vec2 {
    const rect = elemRect(viewportNode.value)
    const canvasPos = clientPos.sub(rect.pos)
    const v = viewport.value
    return new Vec2(
      v.pos.x + v.size.x * (canvasPos.x / rect.size.x),
      v.pos.y + v.size.y * (canvasPos.y / rect.size.y),
    )
  }

  function clientToSceneRect(clientRect: Rect): Rect {
    const rect = elemRect(viewportNode.value)
    const canvasPos = clientRect.pos.sub(rect.pos)
    const v = viewport.value
    const pos = new Vec2(
      v.pos.x + v.size.x * (canvasPos.x / rect.size.x),
      v.pos.y + v.size.y * (canvasPos.y / rect.size.y),
    )
    const size = new Vec2(
      v.size.x * (clientRect.size.x / rect.size.x),
      v.size.y * (clientRect.size.y / rect.size.y),
    )
    return new Rect(pos, size)
  }

  function panAndZoomTo(
    rect: Rect,
    minScale = PAN_AND_ZOOM_DEFAULT_SCALE_RANGE[0],
    maxScale = PAN_AND_ZOOM_DEFAULT_SCALE_RANGE[1],
  ) {
    if (!viewportNode.value) return
    targetScale.value = Math.max(
      minScale,
      Math.min(
        maxScale,
        viewportNode.value.clientHeight / rect.height,
        viewportNode.value.clientWidth / rect.width,
      ),
    )
    targetCenter.value = rect.center().finiteOrZero()
  }

  /** Pan to include the given prioritized list of coordinates.
   *
   *  The view will be offset to include each coordinate, unless the coordinate cannot be fit in the viewport without
   *  losing a previous (higher-priority) coordinate; in that case, shift the viewport as close as possible to the
   *  coordinate while still satisfying the more important constraints.
   *
   *  If all provided constraints can be met, the viewport will be moved the shortest distance that fits all the
   *  coordinates in view.
   */
  function panTo(points: Partial<Vec2>[]) {
    let target = viewport.value
    for (const point of points.reverse()) target = target.offsetToInclude(point) ?? target
    targetCenter.value = target.center()
  }

  /** Pan immediately to center the viewport at the given point, in scene coordinates. */
  function scrollTo(newCenter: Vec2) {
    targetCenter.value = newCenter
    center.skip()
  }

  /** Set viewport center point and scale value immediately, skipping animations. */
  function setCenterAndScale(newCenter: Vec2, newScale: number) {
    targetCenter.value = newCenter
    targetScale.value = newScale
    scale.skip()
    center.skip()
  }

  let zoomPivot = Vec2.Zero
  const zoomPointer = usePointer((pos, _event, ty) => {
    if (ty === 'start') {
      zoomPivot = clientToScenePos(pos.initial)
    }

    const prevScale = scale.value
    updateScale((oldValue) => oldValue * Math.exp(-pos.delta.y / 100))
    scrollTo(
      center.value
        .sub(zoomPivot)
        .scale(prevScale / scale.value)
        .add(zoomPivot),
    )
  }, PointerButtonMask.Secondary)

  const viewport = computed(() => {
    const nodeSize = size.value
    const { x, y } = center.value
    const s = scale.value
    const w = nodeSize.x / s
    const h = nodeSize.y / s
    return new Rect(new Vec2(x - w / 2, y - h / 2), new Vec2(w, h))
  })

  const viewBox = computed(() => {
    const v = viewport.value
    return `${v.pos.x} ${v.pos.y} ${v.size.x} ${v.size.y}`
  })

  const translate = computed<Vec2>(() => {
    const nodeSize = size.value
    const { x, y } = center.value
    const s = scale.value
    const w = nodeSize.x / s
    const h = nodeSize.y / s
    return new Vec2(-x + w / 2, -y + h / 2)
  })

  const transform = computed(
    () => `scale(${scale.value}) translate(${translate.value.x}px, ${translate.value.y}px)`,
  )

  const prescaledTransform = computed(
    () => `translate(${translate.value.x * scale.value}px, ${translate.value.y * scale.value}px)`,
  )

  let isPointerDown = false
  let scrolledThisFrame = false
  const eventMousePos = shallowRef<Vec2 | null>(null)
  let eventTargetScrollPos: Vec2 | null = null
  const sceneMousePos = computed(() =>
    eventMousePos.value ? clientToScenePos(eventMousePos.value) : null,
  )

  useEvent(
    window,
    'scroll',
    (e) => {
      if (
        !isPointerDown ||
        scrolledThisFrame ||
        !eventMousePos.value ||
        !(e.target instanceof Element)
      )
        return
      scrolledThisFrame = true
      requestAnimationFrame(() => (scrolledThisFrame = false))
      if (!(e.target instanceof Element)) return
      const newScrollPos = new Vec2(e.target.scrollLeft, e.target.scrollTop)
      if (eventTargetScrollPos !== null) {
        const delta = newScrollPos.sub(eventTargetScrollPos)
        const mouseDelta = new Vec2(
          (delta.x * e.target.clientWidth) / e.target.scrollWidth,
          (delta.y * e.target.clientHeight) / e.target.scrollHeight,
        )
        eventMousePos.value = eventMousePos.value?.add(mouseDelta) ?? null
      }
      eventTargetScrollPos = newScrollPos
    },
    { capture: true },
  )

  useEvent(
    window,
    'scrollend',
    () => {
      eventTargetScrollPos = null
    },
    { capture: true },
  )

  /** Clamp the value to the given bounds, except if it is already outside the bounds allow the new value to be less
   *  outside the bounds. */
  function directedClamp(oldValue: number, newValue: number, [min, max]: ScaleRange): number {
    if (newValue > oldValue) return Math.min(max, newValue)
    else return Math.max(min, newValue)
  }

  function updateScale(f: (value: number) => number, range: ScaleRange = DEFAULT_SCALE_RANGE) {
    const oldValue = scale.value
    targetScale.value = directedClamp(oldValue, f(oldValue), range)
    scale.skip()
  }

  /** Step to the next level from {@link ZOOM_LEVELS}.
   * @param zoomStepDelta step direction. If positive select larger zoom level; if negative  select smaller.
   * If 0, resets zoom level to 1.0. */
  function stepZoom(zoomStepDelta: number) {
    const oldValue = targetScale.value
    const insideThreshold = (level: number) =>
      Math.abs(oldValue - level) <= level * ZOOM_SKIP_THRESHOLD
    if (zoomStepDelta > 0) {
      const lastZoomLevel = ZOOM_LEVELS[ZOOM_LEVELS.length - 1]!
      targetScale.value =
        ZOOM_LEVELS.find((level) => level > oldValue && !insideThreshold(level)) ?? lastZoomLevel
    } else if (zoomStepDelta < 0) {
      const firstZoomLevel = ZOOM_LEVELS[0]!
      targetScale.value =
        ZOOM_LEVELS_REVERSED.find((level) => level < oldValue && !insideThreshold(level)) ??
        firstZoomLevel
    } else {
      targetScale.value = 1.0
    }
    scale.skip()
  }

  return proxyRefs({
    events: {
      dragover(e: DragEvent) {
        eventMousePos.value = eventScreenPos(e)
      },
      dragleave() {
        eventMousePos.value = null
      },
      pointermove(e: PointerEvent) {
        eventMousePos.value = eventScreenPos(e)
        panPointer.events.pointermove(e)
        zoomPointer.events.pointermove(e)
      },
      pointerleave() {
        eventMousePos.value = null
      },
      pointerup(e: PointerEvent) {
        isPointerDown = false
        panPointer.events.pointerup(e)
        zoomPointer.events.pointerup(e)
      },
      pointerdown(e: PointerEvent) {
        isPointerDown = true
        panPointer.events.pointerdown(e)
        zoomPointer.events.pointerdown(e)
      },
      wheel(e: WheelEvent) {
        e.preventDefault()
        if (e.ctrlKey) {
          // A pinch gesture is represented by setting `e.ctrlKey`. It can be distinguished from an actual Ctrl+wheel
          // combination because the real Ctrl key emits keyup/keydown events.
          const isGesture = !keyboard.ctrl
          if (isGesture) {
            // OS X trackpad events provide usable rate-of-change information.
            updateScale((oldValue: number) => oldValue * Math.exp(-e.deltaY / 100))
          } else {
            // Mouse wheel rate information is unreliable. We just step in the direction of the sign.
            stepZoom(-Math.sign(e.deltaY))
          }
        } else {
          const delta = new Vec2(e.deltaX, e.deltaY)
          scrollTo(center.value.addScaled(delta, 1 / scale.value))
        }
      },
      contextmenu(e: Event) {
        e.preventDefault()
      },
    },
    translate,
    targetCenter: readonly(targetCenter),
    targetScale: readonly(targetScale),
    scale: readonly(toRef(scale, 'value')),
    viewBox,
    transform,
    /** Use this transform instead, if the element should not be scaled. */
    prescaledTransform,
    sceneMousePos,
    clientToScenePos,
    clientToSceneRect,
    panAndZoomTo,
    panTo,
    viewport,
    stepZoom,
    scrollTo,
    setCenterAndScale,
  })
}
