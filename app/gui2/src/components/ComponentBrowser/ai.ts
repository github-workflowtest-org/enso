import { useGraphStore } from '@/stores/graph'
import type { GraphDb } from '@/stores/graph/graphDatabase'
import { useProjectStore } from '@/stores/project'
import type { LanguageServer } from 'shared/languageServer'
import { Err, Ok, withContext, type Result } from 'shared/util/data/result'
import type { ExternalId } from 'shared/yjsModel'

const AI_GOAL_PLACEHOLDER = '__$$GOAL$$__'
const AI_STOP_SEQUENCE = '`'

export function useAI(
  graphDb: GraphDb = useGraphStore().db,
  project: {
    lsRpcConnection: LanguageServer
    executeExpression(expressionId: ExternalId, expression: string): Promise<Result<any> | null>
  } = useProjectStore(),
) {
  async function query(query: string, sourceIdentifier: string): Promise<Result<string>> {
    return withContext(
      () => 'When getting AI completion',
      async () => {
        const lsRpc = project.lsRpcConnection
        const sourceNodeId = graphDb.getIdentDefiningNode(sourceIdentifier)
        const contextId =
          sourceNodeId && graphDb.nodeIdToNode.get(sourceNodeId)?.outerExpr.externalId
        if (!contextId) return Err(`Cannot find node with name ${sourceIdentifier}`)

        const prompt = await withContext(
          () => 'When building AI propt',
          async () => {
            const prompt = await project.executeExpression(
              contextId,
              `Standard.Visualization.AI.build_ai_prompt ${sourceIdentifier} . to_json`,
            )
            if (!prompt) return Err('No data from AI visualization')
            return prompt
          },
        )
        if (!prompt.ok) return prompt
        const promptWithGoal = prompt.value.replace(AI_GOAL_PLACEHOLDER, query)
        const completion = await lsRpc.aiCompletion(promptWithGoal, AI_STOP_SEQUENCE)
        if (!completion.ok) return completion
        return Ok(completion.value.code)
      },
    )
  }

  return {
    query,
  }
}
