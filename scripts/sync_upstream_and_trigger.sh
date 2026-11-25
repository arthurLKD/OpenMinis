#!/bin/sh
set -e

REPO_DIR="/var/minis/workspace/OpenMinis"
BRANCH="feature/openui-support"

if [ ! -d "$REPO_DIR" ]; then
    echo "ERROR: Repo directory $REPO_DIR does not exist."
    exit 1
fi

cd "$REPO_DIR"

if [ -z "$GITHUB_TOKEN" ]; then
    echo "ERROR: GITHUB_TOKEN environment variable is not set."
    exit 1
fi

# Ensure remotes exist
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/OpenMinis/OpenMinis.git

echo "==> Fetching latest origin and upstream main..."
git fetch origin main >/dev/null 2>&1 || true
git fetch upstream main >/dev/null 2>&1 || true
git fetch origin "$BRANCH" >/dev/null 2>&1 || true

# Switch to target feature branch
git checkout "$BRANCH" >/dev/null 2>&1

# Check if upstream/main has new commits compared to what's already merged in our branch
# Look for commits in upstream/main that are not in HEAD
UPSTREAM_NEW_COMMITS=$(git rev-list --count HEAD..upstream/main 2>/dev/null || echo "0")

if [ "$UPSTREAM_NEW_COMMITS" -eq 0 ]; then
    echo "STATUS: NO_UPDATE"
    echo "Upstream main has no new commits since last merge."
    exit 0
fi

echo "STATUS: HAS_UPDATE"
echo "Found $UPSTREAM_NEW_COMMITS new commit(s) from upstream/main."

echo "==> Recent upstream commits:"
git log -n 5 --oneline HEAD..upstream/main

echo "==> Merging upstream/main into $BRANCH..."
# Perform merge
if git merge --no-edit upstream/main; then
    echo "==> Merge successful! Pushing to origin $BRANCH..."
    git push "https://arthurLKD:$GITHUB_TOKEN@github.com/arthurLKD/OpenMinis.git" "$BRANCH"
    echo "STATUS: MERGED_AND_PUSHED"
    echo "New push will automatically trigger GitHub Actions workflow build."
else
    echo "STATUS: MERGE_CONFLICT"
    echo "Conflict detected during merge. Aborting merge to protect workspace."
    git merge --abort || true
    exit 2
fi
