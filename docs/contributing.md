# Contributing

Track work in the [repository issues](https://github.com/luisfm44/caseware-template-fanout-worker/issues) and the [Caseware project board](https://github.com/users/luisfm44/projects/2). Use the issue number to connect the branch, pull request and validation evidence.

## Create an issue

1. Open **Issues → New issue** in the repository.
2. Write a title that describes the outcome. In the description, name the owning component, explain the context, list the changes in scope and define acceptance criteria that a reviewer can verify.
3. Include evidence that helps reproduce or review the work. For a bug, provide the steps, expected and actual behavior, and relevant logs. For a planned change, link the design or related issue and describe how to validate the result.
4. Create the issue and confirm that it appears on the Caseware project board. To add it manually, use the issue's **Projects** field or paste the issue URL into **Add item** on the board. New work starts in **Todo**. [Adding items to a project](https://docs.github.com/en/issues/planning-and-tracking-with-projects/managing-items-in-your-project/adding-items-to-your-project)

## Choose a branch type

Use `<type>/issue-<number>-<slug>`. Choose the type by the primary purpose of the issue, use lowercase words separated by hyphens and keep the slug short.

| Type | Use it for | Example |
| --- | --- | --- |
| `feature` | New behavior or capability | `feature/issue-42-retry-metrics` |
| `fix` | A correction to existing behavior | `fix/issue-43-retry-timeout` |
| `docs` | Documentation, diagrams or Wiki material | `docs/issue-44-worker-guide` |
| `test` | Tests or coverage as the primary change | `test/issue-45-planning-coverage` |
| `refactor` | Internal design changes that preserve behavior | `refactor/issue-46-admission-policy` |
| `ci` | Workflows, pipeline checks or build automation | `ci/issue-47-coverage-artifact` |
| `chore` | Dependency, tooling or repository maintenance | `chore/issue-48-update-maven-wrapper` |

Start an issue branch from the latest `origin/main`. If several issues need one coordinated change, record the shared scope in the PR and state which criteria it satisfies for each issue.

```sh
git fetch origin
git switch -c feature/issue-42-retry-metrics origin/main
```

After committing your changes, publish the branch.

```sh
git push -u origin feature/issue-42-retry-metrics
```

## Move work through the board

| Status | Move the issue here when |
| --- | --- |
| **Todo** | The issue has been recorded and is waiting to start. |
| **In Progress** | Implementation starts. Assign the issue to the developer doing the work and create its branch. |
| **Test** | The implementation is ready for validation. Link its PR, run the relevant checks and attach the results to the PR. Keep the issue here while checks or review are pending. |
| **Done** | The acceptance criteria are met and the linked PR has been squash-merged into `main`. Confirm the issue is closed and the merged PR appears in the board's **Linked pull requests** field. Set **Done** on the board if needed. |

## Link, review and merge the pull request

1. Open a **Draft** pull request from the issue branch to `main` when you want to start CI or share work in progress.
2. Use `Closes #42` for an issue whose full acceptance criteria the PR satisfies. For related work, partial evidence or a handoff to another component, use a plain reference such as `Related to #42`. Use the full issue link for a different repository. GitHub closes an issue linked with a closing keyword when the PR merges into the default branch. [Linking a pull request to an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)
3. Summarize the change and link checks for the commit under review. Open the PR’s automatic **Verify and scan** run to confirm JaCoCo coverage, the SonarQube Quality Gate and the three HTTP suite results. For a reference API change, link its scenario report. For a future regional rollout, link the deployed service and its acceptance run. [Code quality guide](code-quality.md) · [Pipeline guide](pipelines.md) · [Evidence index](evidence/README.md) · [Owner acceptance criteria](owner-acceptance.md)
4. Select **Ready for review** when the implementation and validation are ready. Resolve review feedback and wait for the required `verify` and `security` checks. Then use **Squash and merge** to merge the PR into `main`. [GitHub squash merge reference](https://docs.github.com/en/pull-requests/reference/pull-request-merges#squash-and-merge-your-commits)
5. Confirm that the issue shows the merged PR under **Development** and that the project board shows the issue in **Done** with that PR in **Linked pull requests**.

When a change affects behavior, component boundaries or deployment assumptions, update the versioned architecture and diagrams in the same PR. Update the affected Wiki pages after the changes land on `main`, and verify navigation and source links. [Worker architecture](worker-architecture.md) · [Worker design decisions](worker-design.md) · [System architecture](system-architecture.md) · [Contributor Workflow on the Wiki](https://github.com/luisfm44/caseware-template-fanout-worker/wiki/Contributor-Workflow)
