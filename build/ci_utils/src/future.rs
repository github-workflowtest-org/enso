use crate::prelude::*;

use futures_util::future::OptionFuture;



pub fn receiver_to_stream<T>(
    mut receiver: tokio::sync::mpsc::Receiver<T>,
) -> impl Stream<Item = T> {
    futures::stream::poll_fn(move |ctx| receiver.poll_recv(ctx))
}


#[derive(Copy, Clone, Debug)]
pub enum AsyncPolicy {
    Sequential,
    FutureParallelism,
    TaskParallelism,
}

pub async fn join_all<I, F, T, E>(futures: I, parallel: AsyncPolicy) -> Vec<Result<T>>
where
    I: IntoIterator<Item = F>,
    F: Future<Output = std::result::Result<T, E>> + Send + 'static,
    T: Send + 'static,
    E: Into<anyhow::Error> + Send + 'static, {
    match parallel {
        AsyncPolicy::Sequential => {
            let mut ret = Vec::new();
            for future in futures {
                ret.push(future.await.anyhow_err());
            }
            ret
        }
        AsyncPolicy::FutureParallelism =>
            futures::future::join_all(futures).await.into_iter().map(|r| r.anyhow_err()).collect(),
        AsyncPolicy::TaskParallelism => {
            let tasks = futures
                .into_iter()
                .map(|future| async move { tokio::task::spawn(future).await?.anyhow_err() });
            futures::future::join_all(tasks).await
        }
    }
}

pub async fn try_join_all<I, F, T, E>(futures: I, parallel: AsyncPolicy) -> Result<Vec<T>>
where
    I: IntoIterator<Item = F>,
    F: Future<Output = std::result::Result<T, E>> + Send + 'static,
    T: Send + 'static,
    E: Into<anyhow::Error> + Send + 'static, {
    match parallel {
        AsyncPolicy::Sequential => {
            let mut ret = Vec::new();
            for future in futures {
                ret.push(future.await.anyhow_err()?);
            }
            Ok(ret)
        }
        AsyncPolicy::FutureParallelism => futures::future::try_join_all(futures).await.anyhow_err(),
        AsyncPolicy::TaskParallelism => {
            let tasks = futures
                .into_iter()
                .map(|future| async move { tokio::task::spawn(future).await?.anyhow_err() });
            futures::future::try_join_all(tasks).await
        }
    }
}

pub fn perhaps<F: Future>(should_do: bool, f: impl FnOnce() -> F) -> OptionFuture<F> {
    should_do.then(f).into()
}
